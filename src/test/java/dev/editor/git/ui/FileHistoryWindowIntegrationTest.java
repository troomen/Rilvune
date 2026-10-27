package dev.editor.git.ui;

import dev.editor.diff.infrastructure.*;
import dev.editor.git.application.GitWorkspaceService;
import dev.editor.git.domain.FileHistoryQuery.Lines;
import dev.editor.git.infrastructure.*;
import dev.editor.platform.text.ui.TextEditorSettings;
import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(30)
class FileHistoryWindowIntegrationTest {
    @TempDir Path temporary;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private JFrame owner;
    private FileHistoryWindow window;
    private GitFixture fixture;
    private final String current = "header\nselected new\nfooter\n";

    @BeforeEach void start() throws Exception {
        fixture = new GitFixture(temporary.resolve("repository"));
        fixture.write("old.txt", "header\nselected old\nfooter\n"); fixture.commit("Initial file");
        fixture.write("old.txt", current); fixture.commit("Change selected line");
        fixture.git("mv", "old.txt", "current.txt"); fixture.commit("Rename file");
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install(); owner = new JFrame(); owner.setSize(1100, 800); owner.setVisible(true);
            window = new FileHistoryWindow(owner, new GitWorkspaceService(new CliGitRepository(GitConfiguration.defaults()), workers, SwingUtilities::invokeLater),
                    new MyersDiffEngine(DiffLimits.interactive()), workers, new TextEditorSettings(4, true, false, true, false, true), theme, ignored -> { });
            return null;
        });
    }
    @AfterEach void close() throws Exception {
        edt(() -> { if (window != null) window.close(); if (owner != null) owner.dispose(); return null; }); workers.shutdownNow();
    }
    @Test void fileHistoryReusesGitPresentationAndShowsHistoricalPathsWithoutWrites() throws Exception {
        var before = fixture.snapshot();
        edt(() -> { window.open(fixture.root, fixture.root.resolve("current.txt"), null, null); return null; });
        await(() -> rows() == 3);
        edt(() -> { find("git.history", JTable.class).setRowSelectionInterval(2, 2); return null; });
        await(() -> text("git.diff.right").contains("selected old"));
        assertEquals("", text("git.diff.left"));
        edt(() -> {
            assertFalse(window.isModal());
            assertFalse(find("git.historyWholeFile", JButton.class).isVisible());
            assertFalse(all(window, JTable.class).stream().anyMatch(table -> "git.files".equals(table.getName())));
            assertTrue(all(window, JLabel.class).stream().anyMatch(label -> label.getText().contains("old.txt")));
            JTextArea right = find("git.diff.right", JTextArea.class); String original = right.getText();
            right.setEditable(true); right.selectAll(); right.replaceSelection("BAD"); right.getDocument().insertString(0, "BAD", null);
            assertFalse(right.isEditable()); assertEquals(original, right.getText());
            return null;
        });
        assertEquals(before, fixture.snapshot());
        snapshot("file-history.png");
    }
    @Test void selectionHistoryMapsLocalInsertionAndHighlightsHistoricalRangesAcrossRename() throws Exception {
        edt(() -> { window.open(fixture.root, fixture.root.resolve("current.txt"), "local insertion\n" + current, new Lines(2, 3)); return null; });
        await(() -> rows() == 2);
        await(() -> edt(() -> "selected new\n".equals(find("git.diff.right", JTextArea.class).getSelectedText())));
        assertTrue(text("git.diff.left").contains("selected old"));
        snapshot("selection-history.png");
        edt(() -> {
            assertTrue(find("git.diff.right", JTextArea.class).getCaret().isSelectionVisible());
            find("git.history", JTable.class).setRowSelectionInterval(1, 1); return null;
        });
        await(() -> edt(() -> "selected old\n".equals(find("git.diff.right", JTextArea.class).getSelectedText())));
        assertEquals("", text("git.diff.left"));
    }
    @Test void locallyModifiedSelectionOffersFileHistoryAndClosingClearsPendingScope() throws Exception {
        edt(() -> { window.open(fixture.root, fixture.root.resolve("current.txt"), current.replace("selected new", "local change"), new Lines(1, 2)); return null; });
        await(() -> edt(() -> find("git.historyStatus", JLabel.class).getText().contains("local changes")));
        assertEquals(0, rows());
        edt(() -> { find("git.historyWholeFile", JButton.class).doClick(); return null; }); await(() -> rows() == 3);
        edt(() -> { window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)); return null; });
        assertFalse(edt(window::isVisible)); assertEquals(0, rows());
        assertEquals("", text("git.diff.right"));
        fixture.write("another.txt", "another file\n"); fixture.commit("Another file");
        edt(() -> { window.open(fixture.root, fixture.root.resolve("another.txt"), null, null); return null; });
        await(() -> rows() == 1); await(() -> text("git.diff.right").equals("another file\n"));
    }
    private int rows() throws Exception { return edt(() -> find("git.history", JTable.class).getRowCount()); }
    private void snapshot(String name) throws Exception {
        edt(() -> {
            window.validate(); JComponent root = window.getRootPane();
            var image = new java.awt.image.BufferedImage(root.getWidth(), root.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
            Path output = Path.of("build/ui-snapshots", name); java.nio.file.Files.createDirectories(output.getParent());
            javax.imageio.ImageIO.write(image, "png", output.toFile()); return null;
        });
    }
    private String text(String name) throws Exception { return edt(() -> find(name, JTextArea.class).getText()); }
    private <T extends Component> T find(String name, Class<T> type) { return all(window, type).stream().filter(value -> name.equals(value.getName())).findFirst().orElseThrow(); }
    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container container) result.addAll(all(container, type)); }
        return result;
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) { if (condition.call()) return; Thread.sleep(25); }
        fail("Timed out: " + edt(() -> find("git.historyStatus", JLabel.class).getText()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return task.call();
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
