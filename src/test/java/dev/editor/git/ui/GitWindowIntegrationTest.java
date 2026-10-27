package dev.editor.git.ui;

import dev.editor.git.application.GitWorkspaceService;
import dev.editor.git.application.ProjectGitStatusService;
import dev.editor.git.domain.*;
import dev.editor.git.infrastructure.*;
import dev.editor.diff.infrastructure.*;
import dev.editor.platform.text.ui.TextEditorSettings;
import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
class GitWindowIntegrationTest {
    @TempDir Path temporary;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ProjectGitStatusService projectStatus;
    private JFrame owner;
    private GitWindow window;
    private GitFixture fixture;
    private CliGitRepository repository;
    private final AtomicReference<Path> opened = new AtomicReference<>();
    private final AtomicReference<Path> historyRequested = new AtomicReference<>();
    private final TextEditorSettings settings = new TextEditorSettings(4, true, false, true, false, true);
    @AfterEach void close() throws Exception { if (window != null) { edt(() -> window.flush()).get(5, TimeUnit.SECONDS); edt(() -> { window.close(); return null; }); } if (owner != null) edt(() -> { owner.dispose(); return null; }); if (projectStatus != null) projectStatus.close(); scheduler.shutdownNow(); workers.shutdownNow(); }
    private void start() throws Exception {
        fixture = new GitFixture(temporary.resolve("PREVIEW · Editor"));
        fixture.write("src/config.conf", "# Application configuration\n[server]\nhost = localhost\nport = 8080\nworkers = 2\n\n[features]\nautosave = true\n"); fixture.commit("Create application configuration"); fixture.git("branch", "feature/compare");
        fixture.write("README.md", "# Editor\n\nA quiet place to write.\n"); fixture.commit("Document the workspace model"); fixture.git("tag", "v0.2");
        fixture.write("src/config.conf", "# Application configuration\n[server]\nhost = localhost\nport = 9090\nworkers = 4\ntimeout = 30\n\n[features]\nautosave = true\ncompare = enabled\n"); fixture.write("docs/notes.md", "# Notes\nShared snapshots keep Git read only.\n");
        repository = new CliGitRepository(GitConfiguration.defaults());
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install(); owner = new JFrame("Main editor"); JPanel host = new JPanel(new BorderLayout()); owner.setContentPane(host); owner.setSize(1100, 800); owner.setVisible(true);
            projectStatus = new ProjectGitStatusService(repository, new GitObservation(workers), workers, scheduler, SwingUtilities::invokeLater, ProjectGitStatusService.Configuration.defaults(), failure -> { throw new AssertionError(failure); });
            var panel = new GitWorkspacePanel(new GitWorkspaceService(repository, workers, SwingUtilities::invokeLater), projectStatus, new MyersDiffEngine(DiffLimits.interactive()), workers, settings, theme, opened::set, historyRequested::set, path -> "Editor: SYNCHRONIZING · Git shows disk only");
            window = new GitWindow(owner, panel, content -> { host.removeAll(); if (content != null) host.add(content); host.revalidate(); }, new PropertiesGitViewStateStore(temporary.resolve("profile")), GitViewState.defaults(), workers);
            window.project(fixture.root); projectStatus.project(fixture.root); window.show(); return null;
        });
        await(() -> text("git.diff.right").contains("9090"));
    }
    @Test void changesAreReadOnlyRefreshLiveAndDoNotChangeRepository() throws Exception {
        start(); var before = fixture.snapshot();
        edt(() -> {
            JTextArea right = find(window.workspace(), "git.diff.right", JTextArea.class); String original = right.getText();
            assertFalse(right.isEditable()); right.setEditable(true); assertFalse(right.isEditable()); right.getDocument().insertString(0, "BAD", null); right.selectAll(); right.replaceSelection("BAD"); right.paste();
            assertEquals(original, right.getText()); assertFalse(all(window.workspace(), JButton.class).stream().anyMatch(button -> "diff.useLeft".equals(button.getName()) || "diff.useRight".equals(button.getName())));
            JTable files = find(window.workspace(), "git.files", JTable.class); assertEquals(1, files.getRowCount());
            return null;
        });
        assertEquals(before, fixture.snapshot()); fixture.write("src/config.conf", "external refresh\n");
        await(() -> text("git.diff.right").equals("external refresh\n"));
        edt(() -> { window.hide(); return null; }); fixture.write("src/config.conf", "while hidden\n"); edt(() -> { window.show(); return null; }); await(() -> text("git.diff.right").equals("while hidden\n"));
        edt(() -> { find(window.workspace(), "git.comparison", JComboBox.class).setSelectedIndex(1); return null; }); await(() -> rows("git.files") == 0);
        fixture.git("add", "src/config.conf"); edt(() -> { window.workspace().refresh(); return null; }); await(() -> rows("git.files") == 1); await(() -> text("git.diff.right").equals("while hidden\n"));
    }
    @Test void localChangesExcludeUntrackedFilesUntilAddedToTheIndex() throws Exception {
        start();
        assertTrue(repository.status(repository.locate(fixture.root)).changes().stream()
                .anyMatch(change -> change.path().equals("docs/notes.md") && change.untracked()),
                "Shared Git status must retain untracked files");
        await(() -> edt(() -> window.workspace().statusText().contains("1 tracked local paths")));
        edt(() -> { find(window.workspace(), "git.comparison", JComboBox.class).setSelectedIndex(2); return null; });
        assertEquals(1, rows("git.files"));
        edt(() -> { find(window.workspace(), "git.comparison", JComboBox.class).setSelectedIndex(1); return null; });
        assertEquals(0, rows("git.files"));

        fixture.git("add", "docs/notes.md");
        edt(() -> { window.workspace().refresh(); return null; });
        await(() -> rows("git.files") == 1);
        edt(() -> { find(window.workspace(), "git.files", JTable.class).setRowSelectionInterval(0, 0); return null; });
        await(() -> text("git.diff.right").contains("Shared snapshots"));
        edt(() -> { window.showLocalChanges(); return null; });
        assertEquals(2, rows("git.files"));
        edt(() -> { find(window.workspace(), "git.comparison", JComboBox.class).setSelectedIndex(2); return null; });
        assertEquals(1, rows("git.files"));

        fixture.write("docs/notes.md", "unstaged edit to newly tracked file\n");
        edt(() -> { window.workspace().refresh(); return null; });
        await(() -> rows("git.files") == 2);
        fixture.git("reset", "HEAD", "--", "docs/notes.md");
        edt(() -> { window.workspace().refresh(); return null; });
        await(() -> rows("git.files") == 1);
        edt(() -> { window.showLocalChanges(); return null; });
        assertEquals(1, rows("git.files"));
        edt(() -> { find(window.workspace(), "git.comparison", JComboBox.class).setSelectedIndex(1); return null; });
        assertEquals(0, rows("git.files"));
    }
    @Test void fileHistoryActionOpensTheContextualViewWithoutReplacingLocalChanges() throws Exception {
        start();
        edt(() -> {
            JTable files = find(window.workspace(), "git.files", JTable.class);
            Rectangle rect = files.getCellRect(files.getSelectedRow(), 1, true);
            files.dispatchEvent(new MouseEvent(files, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                    rect.x + 8, rect.y + rect.height / 2, 1, true, MouseEvent.BUTTON3));
            JPopupMenu menu = Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).filter(JPopupMenu.class::isInstance)
                    .map(JPopupMenu.class::cast).findFirst().orElseThrow();
            Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                    .filter(item -> "Show File History".equals(item.getText())).findFirst().orElseThrow().doClick();
            MenuSelectionManager.defaultManager().clearSelectedPath();
            assertEquals(0, find(window.workspace(), "git.mode", JComboBox.class).getSelectedIndex());
            assertTrue(files.isShowing()); return null;
        });
        assertEquals(fixture.root.resolve("src/config.conf"), historyRequested.get()); assertNull(opened.get());
    }
    @Test void switchingHistoricalFilesPaintsTextAfterEverySnapshotReplacement() throws Exception {
        start();
        List<String> paths = List.of("short.java", "long.java", "medium.java"); int[] lengths = {8, 160, 45};
        for (int i = 0; i < paths.size(); i++) fixture.write(paths.get(i), "before " + paths.get(i) + "\n" + "unchanged context\n".repeat(lengths[i]));
        fixture.commit("Before multiple file changes");
        for (int i = 0; i < paths.size(); i++) fixture.write(paths.get(i), "after " + paths.get(i) + "\nnew line\n" + "unchanged context\n".repeat(lengths[i]));
        fixture.commit("Change files of different sizes");
        edt(() -> { find(window.workspace(), "git.mode", JComboBox.class).setSelectedIndex(1); window.workspace().refresh(); return null; }); await(() -> rows("git.history") == 4);
        edt(() -> { find(window.workspace(), "git.mode", JComboBox.class).setSelectedIndex(1); find(window.workspace(), "git.history", JTable.class).setRowSelectionInterval(0, 0); return null; });
        await(() -> rows("git.files") == 3);
        for (String name : List.of("long.java", "short.java", "medium.java", "long.java")) {
            edt(() -> { JTable table = find(window.workspace(), "git.files", JTable.class); for (int row = 0; row < table.getRowCount(); row++) if (name.equals(table.getValueAt(row, 1))) { table.setRowSelectionInterval(row, row); break; } return null; });
            await(() -> text("git.diff.right").startsWith("after " + name));
            await(() -> edt(() -> all(window.workspace(), dev.editor.diff.ui.DiffView.class).getFirst().result().isPresent()));
            edt(() -> {
                window.frame().validate(); JTextArea area = find(window.workspace(), "git.diff.right", JTextArea.class);
                BufferedImage image = new BufferedImage(area.getWidth(), Math.min(area.getHeight(), 90), BufferedImage.TYPE_INT_RGB); Graphics2D graphics = image.createGraphics(); area.printAll(graphics); graphics.dispose();
                int textPixels = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) { Color color = new Color(image.getRGB(x, y)); if (color.getRed() > 100 && Math.abs(color.getRed() - color.getGreen()) < 25 && Math.abs(color.getBlue() - color.getGreen()) < 25) textPixels++; }
                assertTrue(textPixels > 100, "Historical file must visibly paint source text: " + name); return null;
            });
        }
    }
    @Test void routineRefreshDoesNotFlashUpdatingInTheFooter() throws Exception {
        start(); await(() -> edt(() -> window.workspace().statusText().startsWith("main")));
        List<String> transitions = new ArrayList<>();
        edt(() -> { find(window.workspace(), "git.status", JLabel.class).addPropertyChangeListener("text", event -> transitions.add(String.valueOf(event.getNewValue()))); return null; });
        for (int i = 0; i < 3; i++) { edt(() -> { window.workspace().refresh(); return null; }); Thread.sleep(120); }
        Thread.sleep(3200); // Include the periodic reconciliation, not only explicitly requested refreshes.
        edt(() -> { assertTrue(transitions.stream().noneMatch(value -> value.startsWith("Updating")), transitions.toString()); assertTrue(window.workspace().statusText().startsWith("main")); return null; });
    }
    @Test void sameWorkspaceSurvivesDockingSettingsAndWindowReopen() throws Exception {
        start(); GitWorkspacePanel original = window.workspace();
        edt(() -> {
            JTextArea right = find(original, "git.diff.right", JTextArea.class); right.setCaretPosition(8); right.moveCaretPosition(18);
            window.setDocked(true); assertSame(original, window.workspace()); assertTrue(SwingUtilities.isDescendingFrom(original, owner)); assertFalse(window.frame().isVisible());
            window.setDocked(false); assertSame(original, window.workspace()); assertTrue(window.frame().isVisible());
            EditorTheme theme = EditorTheme.graphite(); var updated = new EditorTheme(theme.chrome(), theme.surface(), theme.raised(), theme.border(), theme.text(), theme.muted(), theme.accent(), theme.selection(), theme.interfaceFont().deriveFont(18f), theme.codeFont().deriveFont(20f));
            window.preserveViewState(() -> window.applySettings(settings, updated)); assertEquals(20, right.getFont().getSize()); assertEquals(8, right.getSelectionStart()); assertEquals(18, right.getSelectionEnd());
            window.hide(); assertTrue(owner.isDisplayable()); window.show(); return null;
        });
        edt(() -> window.flush()).get(5, TimeUnit.SECONDS); assertFalse(new PropertiesGitViewStateStore(temporary.resolve("profile")).load().docked());
    }
    @Test void historyFiltersParentSelectionAndFixedComparisons() throws Exception {
        start(); edt(() -> { find(window.workspace(), "git.mode", JComboBox.class).setSelectedIndex(1); return null; }); await(() -> rows("git.history") == 2);
        edt(() -> { JComboBox<?> mode = find(window.workspace(), "git.mode", JComboBox.class); mode.setSelectedIndex(1); JTable history = find(window.workspace(), "git.history", JTable.class); history.setRowSelectionInterval(0, 0); return null; });
        await(() -> rows("git.files") == 1); await(() -> text("git.diff.right").contains("quiet place")); String historical = text("git.diff.right");
        fixture.write("README.md", "later working contents\n"); fixture.commit("A later commit"); edt(() -> { window.workspace().refresh(); return null; }); await(() -> rows("git.history") == 3); assertEquals(historical, text("git.diff.right"));
        edt(() -> { find(window.workspace(), "git.messageFilter", JTextField.class).setText("Create application"); return null; }); await(() -> rows("git.history") == 1);
        edt(() -> { JTable table = find(window.workspace(), "git.history", JTable.class); table.setRowSelectionInterval(0, 0); return null; }); await(() -> text("git.diff.right").contains("8080")); assertEquals("", text("git.diff.left"));
        RepositoryLocation repo = repository.locate(fixture.root); RepositoryState state = repository.state(repo); GitCommit a = repository.commit(repo, fixture.git("rev-list", "--max-parents=0", "HEAD")), b = repository.commit(repo, state.head());
        edt(() -> { window.workspace().compareCommits(a.header(), b.header()); return null; }); await(() -> rows("git.files") >= 1);
    }
    @Test void projectSwitchAndExplicitFileOpening() throws Exception {
        start();
        edt(() -> { JTable files = find(window.workspace(), "git.files", JTable.class); int row = files.getSelectedRow(); Rectangle rect = files.getCellRect(row, 2, true); files.dispatchEvent(new MouseEvent(files, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, rect.x + Math.max(1, rect.width - 8), rect.y + rect.height / 2, 2, false)); return null; });
        assertNull(opened.get(), "Double-click must stay in the Git viewer");
        edt(() -> {
            JTable files = find(window.workspace(), "git.files", JTable.class); Rectangle rect = files.getCellRect(files.getSelectedRow(), 2, true);
            files.dispatchEvent(new MouseEvent(files, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, rect.x + Math.max(1, rect.width - 8), rect.y + rect.height / 2, 1, true, MouseEvent.BUTTON3));
            JPopupMenu menu = Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).filter(JPopupMenu.class::isInstance).map(JPopupMenu.class::cast).findFirst().orElseThrow();
            Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast).filter(item -> "Open working file in editor".equals(item.getText())).findFirst().orElseThrow().doClick();
            MenuSelectionManager.defaultManager().clearSelectedPath(); return null;
        });
        assertEquals(fixture.root.resolve("src/config.conf"), opened.get());
        Path plain = temporary.resolve("plain"); Files.createDirectories(plain); edt(() -> { window.project(plain); projectStatus.project(plain); return null; }); await(() -> window.workspace().statusText().contains("no Git")); assertEquals("", text("git.diff.right"));
        edt(() -> { window.project(fixture.root); projectStatus.project(fixture.root); return null; }); await(() -> text("git.diff.right").contains("9090"));
    }
    @Test void rendersGitPreview() throws Exception {
        start(); edt(() -> { find(window.workspace(), "git.mode", JComboBox.class).setSelectedIndex(1); return null; }); await(() -> rows("git.history") == 2);
        for (String subject : List.of("Extract shared text interaction", "Keep document persistence explicit", "Add bounded snapshot comparisons", "Refine inline diff ranges", "Preserve view positions on refresh", "Add read-only repository queries", "Compose the Git workspace")) fixture.git("commit", "--allow-empty", "-m", subject);
        fixture.git("update-ref", "refs/remotes/origin/main", fixture.git("rev-parse", "HEAD"));
        edt(() -> { window.workspace().refresh(); return null; }); await(() -> rows("git.history") == 9);
        edt(() -> { window.frame().setSize(1440, 900); window.frame().validate(); return null; }); Thread.sleep(400);
        edt(() -> { JComponent root = window.frame().getRootPane(); BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
            JTable table = find(window.workspace(), "git.history", JTable.class); Point origin = SwingUtilities.convertPoint(table, 0, 0, root); int blue = 0;
            for (int y = origin.y; y < Math.min(image.getHeight(), origin.y + table.getRowHeight() * table.getRowCount()); y++) for (int x = origin.x; x < origin.x + table.getColumnModel().getColumn(0).getWidth(); x++) {
                Color color = new Color(image.getRGB(x, y)); if (color.getBlue() > 140 && color.getBlue() > color.getRed() * 1.2 && color.getBlue() > color.getGreen() * 1.05) blue++;
            }
            assertTrue(blue > 20, "The actual history graph must paint its commit nodes and parent edges");
            Path output = Path.of("build/ui-snapshots/git-window.png"); Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile()); return null; });
    }
    private int rows(String name) throws Exception { return edt(() -> find(window.workspace(), name, JTable.class).getRowCount()); }
    private String text(String name) throws Exception { return edt(() -> find(window.workspace(), name, JTextArea.class).getText()); }
    private void await(CheckedBoolean condition) throws Exception { long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (System.nanoTime() < until) { if (condition.get()) return; Thread.sleep(30); } fail("Timed out: " + edt(() -> window.workspace().statusText())); }
    private static <T extends Component> T find(Container root, String name, Class<T> type) { return all(root, type).stream().filter(component -> name.equals(component.getName())).findFirst().orElseThrow(() -> new AssertionError(name)); }
    private static <T extends Component> List<T> all(Container root, Class<T> type) { List<T> found = new ArrayList<>(); for (Component child : root.getComponents()) { if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container container) found.addAll(all(container, type)); } return found; }
    private static <T> T edt(Callable<T> task) throws Exception { if (SwingUtilities.isEventDispatchThread()) return task.call(); FutureTask<T> future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get(); }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
}
