package dev.rilvune.app.ui;

import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import dev.rilvune.document.ui.DocumentBindings;
import dev.rilvune.document.ui.EditorView;
import dev.rilvune.git.application.GitWorkspaceService;
import dev.rilvune.git.application.ProjectGitStatusService;
import dev.rilvune.git.infrastructure.*;
import dev.rilvune.git.ui.*;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.text.domain.syntax.*;
import dev.rilvune.platform.text.infrastructure.syntax.rsta.RstaSyntaxProvider;
import dev.rilvune.platform.text.ui.syntax.*;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import dev.rilvune.session.infrastructure.SessionRepository;
import dev.rilvune.settings.application.SettingsService;
import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.settings.infrastructure.PropertiesSettingsStore;
import dev.rilvune.workspace.infrastructure.NioWorkspaceFiles;
import dev.rilvune.workspace.infrastructure.WorkspaceOperations;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import javax.swing.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(45)
class MainWindowIntegrationTest {
    @TempDir Path temporary;
    private final List<MainWindow> windows = new ArrayList<>();
    private final java.util.Map<MainWindow, DocumentService> documentServices = new java.util.HashMap<>();
    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }
    @Test void missingOrUntrustedAttachmentLinksShowAnErrorWithoutOpeningEditableFiles() throws Exception {
        Path browse = Files.createDirectories(temporary.resolve("browse"));
        Path profile = temporary.resolve("profile");
        Path directory = Files.createDirectories(profile.resolve("agents/attachments"));
        Path outside = Files.writeString(browse.resolve("source.txt"), "Source must remain untouched");
        Path missing = directory.resolve("a".repeat(64) + ".txt");
        Path symlink = Files.createSymbolicLink(directory.resolve("b".repeat(64) + ".txt"), outside);
        MainWindow window = window(browse, browse, profile);
        for (Path path : List.of(missing, outside, symlink)) {
            CompletableFuture<Void> opening = edt(() -> window.openAttachmentSnapshot(path, "source.txt", "text/plain"));
            JDialog dialog = awaitComponent(JDialog.class, candidate -> "Open attachment".equals(candidate.getTitle()));
            edt(() -> { dialog.dispose(); return null; });
            assertThrows(ExecutionException.class, () -> opening.get(8, TimeUnit.SECONDS));
            assertTrue(edt(() -> window.editorTabs().tabs().isEmpty()));
        }
        assertFalse(Files.exists(missing)); assertEquals("Source must remain untouched", Files.readString(outside));
    }
    @Test void toolbarRowIsAbsentAndAttachmentSnapshotsOutsideBrowseRootOpenWithoutMutableOwnership() throws Exception {
        Path browse = Files.createDirectories(temporary.resolve("browse"));
        Path project = Files.createDirectories(browse.resolve("project"));
        Path profile = temporary.resolve("private-profile");
        var store = new dev.rilvune.agents.infrastructure.persistence.FileAgentAttachmentStore(profile);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        var image = store.saveImage(bytes.toByteArray());
        var attachment = new dev.rilvune.agents.domain.content.AgentContent.Attachment(image.name(), image.mimeType(), store.resolve(image));
        MainWindow window = window(browse, project, profile);
        edt(() -> {
            assertNull(((BorderLayout) window.getContentPane().getLayout()).getLayoutComponent(BorderLayout.NORTH));
            assertNotNull(menu(window, "File")); assertNotNull(menu(window, "Search"));
            assertFalse(components(window, JButton.class).stream().anyMatch(button -> "project.switcher".equals(button.getName())
                    || "Find file · Ctrl+Shift+N".equals(button.getToolTipText())));
            return window.openAttachmentSnapshot(attachment.file(), attachment.name(), attachment.mimeType());
        }).get(8, TimeUnit.SECONDS);
        edt(() -> {
            var view = assertInstanceOf(dev.rilvune.image.ui.ImageTabView.class, window.editorTabs().activeTab().orElseThrow());
            assertEquals(dev.rilvune.image.domain.ImageTabState.Phase.READY, view.controller().model().state().phase());
            assertEquals("Pasted image.png", view.presentation().title());
            assertTrue(window.editorTabs().views().isEmpty());
            return window.openAttachmentSnapshot(attachment.file(), attachment.name(), attachment.mimeType());
        }).get(8, TimeUnit.SECONDS);
        assertEquals(1, edt(() -> window.editorTabs().tabs().size()));
        assertArrayEquals(bytes.toByteArray(), Files.readAllBytes(store.resolve(image)));
        var text = store.importContent("source.txt", "Saved source\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        edt(() -> window.openAttachmentSnapshot(store.resolve(text), text.name(), text.mimeType())).get(8, TimeUnit.SECONDS);
        edt(() -> {
            var view = window.editorTabs().active().orElseThrow();
            assertNull(view.session().path()); assertEquals("Saved source\n", view.textComponent().getText());
            view.textComponent().append("Draft edit\n"); return null;
        });
        assertEquals("Saved source\n", Files.readString(store.resolve(text)));
        edt(window::finishSnapshot).get(8, TimeUnit.SECONDS);
        MainWindow reopened = window(browse, project, profile);
        await(() -> edtUnchecked(() -> reopened.editorTabs().tabs().stream().anyMatch(tab -> tab instanceof dev.rilvune.image.ui.ImageTabView view
                && view.controller().model().state().phase() == dev.rilvune.image.domain.ImageTabState.Phase.READY)));
        assertArrayEquals(bytes.toByteArray(), Files.readAllBytes(store.resolve(image)));
    }
    @Test void searchIsDiscoverableAndFileContextReplacementOpensTheSelectedFile() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path file = Files.writeString(project.resolve("example.txt"), "example text\n");
        MainWindow window = window(temporary, project, temporary.resolve("profile"));
        edt(() -> window.projectTree().select(file)).get(8, TimeUnit.SECONDS);
        edt(() -> {
            JMenu search = java.util.stream.IntStream.range(0, window.getJMenuBar().getMenuCount())
                    .mapToObj(window.getJMenuBar()::getMenu).filter(menu -> "Search".equals(menu.getText())).findFirst().orElseThrow();
            List<String> labels = java.util.Arrays.stream(search.getMenuComponents()).filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast).map(JMenuItem::getText).toList();
            assertTrue(labels.containsAll(List.of("Find in file…", "Replace in file…", "Find in project…", "Replace in project…")));
            assertNull(window.getRootPane().getActionMap().get("file.create"));
            assertFalse(components(window, JButton.class).stream().anyMatch(button -> "New file".equals(button.getToolTipText())));
            JPopupMenu context = window.projectTree().contextMenuForSelection();
            components(context, JMenuItem.class).stream().filter(item -> "Replace in file…".equals(item.getText())).findFirst().orElseThrow().doClick();
            return null;
        });
        await(() -> edtUnchecked(() -> window.editorTabs().active().map(view -> file.equals(view.session().path())
                && components(view, JButton.class).stream().anyMatch(button -> "Replace all".equals(button.getText()) && button.isShowing())).orElse(false)));
    }
    @AfterEach void close() throws Exception {
        edt(() -> { for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose(); return null; });
        for (MainWindow window : windows) if (edt(window::isDisplayable)) edt(window::finishSnapshot).get(10, TimeUnit.SECONDS);
    }
    @Test void agentDockUsesTheFeatureHeaderAndCanBeHiddenWithoutReopeningIt() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        MainWindow window = window(temporary, project, temporary.resolve("profile"));
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite();
            JButton hide = dev.rilvune.platform.ui.UiComponents.iconButton(theme,
                    dev.rilvune.platform.ui.Glyph.Kind.MINUS, "Hide AI Assistant", window::hideAgents);
            JPanel view = new JPanel(new BorderLayout());
            view.add(dev.rilvune.platform.ui.UiComponents.header(theme, "AI Assistant", hide), BorderLayout.NORTH);
            window.installAgents(view, new dev.rilvune.app.application.ProjectLifecycle.Participant() {
                public String name() { return "AI Assistant"; }
                public CompletableFuture<Void> quiesce() { return CompletableFuture.completedFuture(null); }
                public void resume(dev.rilvune.workspace.domain.ProjectContext context) { }
            }, ignored -> { }, () -> { });
            command(window, "view.agents"); window.validate();
            assertTrue(view.isShowing());
            assertEquals(1, components(window, JLabel.class).stream().filter(label -> label.isShowing() && "AI Assistant".equals(label.getText())).count());
            assertFalse(components(window, JLabel.class).stream().anyMatch(label -> label.isShowing() && "Agents".equals(label.getText())));
            hide.doClick(); assertFalse(view.isShowing());
            window.hideAgents(); assertFalse(view.isShowing(), "Hiding an already hidden panel must not reopen it");
            command(window, "view.agents"); assertTrue(view.isShowing()); return null;
        });
    }
    @Test void contextualHistoryAndLocalChangesShareGitPresentationAndClearOnProjectClose() throws Exception {
        var fixture = new dev.rilvune.git.infrastructure.GitFixture(temporary.resolve("project"));
        fixture.write("sample.txt", "header\nselected old\nfooter\n"); fixture.commit("Initial file");
        fixture.write("sample.txt", "header\nselected new\nfooter\n"); fixture.commit("Change selected line");
        String source = "local insertion\nheader\nselected new\nfooter\n";
        fixture.write("sample.txt", source);
        MainWindow window = window(temporary, fixture.root, temporary.resolve("profile"));
        assertEquals("project — Rilvune", edt(window::getTitle));
        assertTrue(edt(() -> components(window, JButton.class).stream().noneMatch(button -> "project.switcher".equals(button.getName()))));
        edt(() -> window.openFile(fixture.root.resolve("sample.txt"))).get(8, TimeUnit.SECONDS);
        await(() -> edtUnchecked(() -> window.getRootPane().getActionMap().get("view.gitChanges").isEnabled()));
        edt(() -> {
            var view = window.editorTabs().active().orElseThrow(); JTextArea text = view.textComponent();
            int start = source.indexOf("selected new"); text.select(start, start + "selected new".length());
            JPopupMenu menu = text.getComponentPopupMenu();
            components(menu, JMenuItem.class).stream().filter(item -> "Show Selection History".equals(item.getText())).findFirst().orElseThrow().doClick();
            return null;
        });
        FileHistoryWindow history = awaitComponent(FileHistoryWindow.class, Window::isShowing);
        await(() -> edtUnchecked(() -> components(history, JTable.class).stream().filter(table -> "git.history".equals(table.getName())).findFirst().orElseThrow().getRowCount() == 2));
        await(() -> edtUnchecked(() -> components(history, JTextArea.class).stream().anyMatch(area -> "git.diff.right".equals(area.getName()) && "selected new\n".equals(area.getSelectedText()))));
        edt(() -> { command(window, "git.fileHistory"); return null; });
        await(() -> edtUnchecked(() -> components(history, JLabel.class).stream().anyMatch(label -> "git.historyScope".equals(label.getName()) && label.getText().startsWith("File:"))));
        edt(() -> {
            assertEquals("view.gitChanges", window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("alt 0")));
            command(window, "view.gitChanges"); return null;
        });
        JFrame gitFrame = awaitComponent(JFrame.class, candidate -> candidate.getTitle().startsWith("Git —"));
        await(() -> edtUnchecked(() -> components(gitFrame, JTable.class).stream().anyMatch(table -> "git.files".equals(table.getName()) && table.isShowing() && table.getRowCount() == 1)));
        assertFalse(edt(() -> components(gitFrame, JTable.class).stream().anyMatch(table -> "git.history".equals(table.getName()) && table.isShowing())));
        edt(() -> {
            assertEquals("Git History", window.getRootPane().getActionMap().get("view.git").getValue(Action.NAME));
            assertEquals("view.git", window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("alt 9")));
            command(window, "view.git"); return null;
        });
        await(() -> edtUnchecked(() -> components(gitFrame, JTable.class).stream().anyMatch(table -> "git.history".equals(table.getName()) && table.isShowing() && table.getRowCount() == 2)));
        edt(() -> { command(window, "view.gitChanges"); return null; });
        await(() -> edtUnchecked(() -> components(gitFrame, JTable.class).stream().anyMatch(table -> "git.files".equals(table.getName()) && table.isShowing() && table.getRowCount() == 1)));
        assertFalse(edt(() -> components(gitFrame, JTable.class).stream().anyMatch(table -> "git.history".equals(table.getName()) && table.isShowing())));
        edt(() -> { gitFrame.dispatchEvent(new WindowEvent(gitFrame, WindowEvent.WINDOW_CLOSING)); command(window, "view.git"); return null; });
        await(() -> edtUnchecked(() -> components(gitFrame, JTable.class).stream().anyMatch(table -> "git.history".equals(table.getName()) && table.isShowing() && table.getRowCount() == 2)));
        edt(() -> { command(window, "project.close"); return null; });
        await(() -> edtUnchecked(() -> window.currentWorkspace() == null));
        assertEquals("Rilvune", edt(window::getTitle));
        assertTrue(edt(() -> components(window, JLabel.class).stream().anyMatch(label -> "Welcome to Rilvune".equals(label.getText()))));
        assertTrue(edt(() -> components(window, JButton.class).stream().noneMatch(button -> "R ▾".equals(button.getText()))));
        assertFalse(edt(history::isVisible));
        assertTrue(edt(() -> components(history, JTextArea.class).stream().allMatch(area -> area.getText().isEmpty())));
    }
    @Test void syntaxHighlightingIsWiredThroughOrdinaryLiveComparisonsAndGlobalSettings() throws Exception {
        Path java = Files.writeString(temporary.resolve("Example.java"), "public class Example { int n = 42; }\n");
        Path python = Files.writeString(temporary.resolve("example.py"), "def example():\n    return 42\n");
        Path profile = temporary.resolve("profile"); MainWindow window = window(temporary, temporary, profile);
        edt(() -> window.openFile(java)).get(8, TimeUnit.SECONDS);
        EditorView ordinary = edt(() -> window.editorTabs().active().orElseThrow());
        await(() -> edtUnchecked(() -> ordinary.textEditor().syntax().status().state() == SyntaxStatus.State.READY));
        assertEquals(new SyntaxLanguageId("java"), edt(() -> ordinary.textEditor().syntax().language()));
        var comparison = edt(() -> window.openComparison(java, python)).get(8, TimeUnit.SECONDS);
        comparison.ready().get(8, TimeUnit.SECONDS);
        await(() -> edtUnchecked(() -> comparison.leftView().textEditor().syntax().status().state() == SyntaxStatus.State.READY
                && comparison.rightView().textEditor().syntax().status().state() == SyntaxStatus.State.READY));
        edt(() -> {
            assertSame(ordinary.textComponent().getDocument(), comparison.leftView().textComponent().getDocument());
            assertSame(ordinary.undoHistory(), comparison.leftView().undoHistory());
            assertEquals(new SyntaxLanguageId("python"), comparison.rightView().textEditor().syntax().language());
            var popup = comparison.leftView().textComponent().getComponentPopupMenu();
            assertFalse(components(popup, JMenu.class).stream().anyMatch(menu -> "text.syntax".equals(menu.getName())));
            assertFalse(components(popup, JMenuItem.class).stream().anyMatch(item -> "text.syntax.status".equals(item.getName())
                    || item.getText().contains("highlighting") || item.getText().contains("Plain text")));
            assertEquals(dev.rilvune.platform.text.domain.syntax.SyntaxSelection.AUTO, comparison.leftView().textEditor().syntax().selection());
            return null;
        });
        var dialog = edt(() -> { var value = window.settingsDialog(); value.selectPage("highlighting"); value.setModal(false); value.setVisible(true); return value; });
        try {
            var preview = edt(() -> components(dialog, dev.rilvune.platform.text.ui.TextEditor.class).stream()
                    .filter(value -> "settingsHighlightingPreview".equals(value.getName())).findFirst().orElseThrow());
            await(() -> edtUnchecked(() -> preview.syntax().status().state() == SyntaxStatus.State.READY));
            snapshot(dialog.getRootPane(), Path.of("build/ui-snapshots/settings-highlighting.png"));
            edt(() -> {
                assertFalse(preview.textComponent().isEditable());
                assertEquals(new SyntaxLanguageId("java"), preview.syntax().language());
                assertTrue(components(dialog, JCheckBox.class).stream().anyMatch(box -> "settingsSyntaxHighlighting".equals(box.getName()) && box.isShowing()));
                dialog.selectPage("general");
                assertFalse(components(dialog, JCheckBox.class).stream().anyMatch(box -> "settingsSyntaxHighlighting".equals(box.getName()) && box.isShowing()));
                dialog.selectPage("highlighting");
                return null;
            });
            edt(() -> {
                components(dialog, JCheckBox.class).stream().filter(box -> "settingsSyntaxHighlighting".equals(box.getName())).findFirst().orElseThrow().doClick();
                assertNull(preview.syntax().result());
                assertTrue(ordinary.textEditor().settings().syntaxHighlighting());
                assertTrue(new PropertiesSettingsStore(profile).load().settings().syntaxHighlighting());
                components(dialog, JButton.class).stream().filter(button -> "settingsApply".equals(button.getName())).findFirst().orElseThrow().doClick(); return null;
            });
            await(() -> edtUnchecked(() -> window.editorTabs().allViews().stream().allMatch(view -> !view.textEditor().settings().syntaxHighlighting())));
            assertFalse(new PropertiesSettingsStore(profile).load().settings().syntaxHighlighting());
            edt(() -> {
                assertNull(comparison.leftView().textEditor().syntax().result()); assertNull(comparison.rightView().textEditor().syntax().result()); return null;
            });
            await(() -> edtUnchecked(() -> components(dialog, JCheckBox.class).stream().anyMatch(box -> "settingsSyntaxHighlighting".equals(box.getName()) && box.isEnabled())));
            edt(() -> {
                components(dialog, JCheckBox.class).stream().filter(box -> "settingsSyntaxHighlighting".equals(box.getName())).findFirst().orElseThrow().doClick();
                components(dialog, JButton.class).stream().filter(button -> "settingsApply".equals(button.getName())).findFirst().orElseThrow().doClick(); return null;
            });
            await(() -> edtUnchecked(() -> comparison.leftView().textEditor().syntax().status().state() == SyntaxStatus.State.READY
                    && comparison.rightView().textEditor().syntax().status().state() == SyntaxStatus.State.READY));
            await(() -> edtUnchecked(() -> components(dialog, JButton.class).stream().anyMatch(button -> "settingsCancel".equals(button.getName()) && button.isEnabled())));
            edt(() -> {
                components(dialog, JCheckBox.class).stream().filter(box -> "settingsSyntaxHighlighting".equals(box.getName())).findFirst().orElseThrow().doClick();
                components(dialog, JButton.class).stream().filter(button -> "settingsCancel".equals(button.getName())).findFirst().orElseThrow().doClick();
                assertTrue(ordinary.textEditor().settings().syntaxHighlighting());
                assertTrue(new PropertiesSettingsStore(profile).load().settings().syntaxHighlighting());
                return null;
            });
        } finally { edt(() -> { dialog.dispose(); return null; }); }
        snapshot(window.snapshotComponent(), Path.of("build/ui-snapshots/syntax-live-comparison.png"));
    }
    @Test void tabLabelsKeyboardAndOpeningExistingFileAlwaysSelectTheRightDocument() throws Exception {
        Path first = Files.writeString(temporary.resolve("first.txt"), "first\n"); Path second = Files.writeString(temporary.resolve("second.txt"), "second\n");
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS); edt(() -> window.openFile(second)).get(8, TimeUnit.SECONDS);
        edt(() -> {
            JTabbedPane tabs = components(window, JTabbedPane.class).getFirst();
            JLabel label = components((Container) tabs.getTabComponentAt(0), JLabel.class).getFirst();
            label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 4, 4, 1, false, MouseEvent.BUTTON1));
            assertEquals(first, window.editorTabs().active().orElseThrow().session().path()); return null;
        });
        await(() -> edtUnchecked(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == window.editorTabs().active().orElseThrow().textComponent()));
        Robot robot = new Robot(); robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_CONTROL);
        await(() -> edtUnchecked(() -> window.editorTabs().active().orElseThrow().session().path().equals(second)));
        edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS);
        assertEquals(first, edt(() -> window.editorTabs().active().orElseThrow().session().path())); assertEquals(2, edt(() -> window.editorTabs().views().size()));
        CompletableFuture<Void> reopened = edt(() -> {
            window.editorTabs().requestClose(window.editorTabs().active().orElseThrow());
            return window.openFile(first);
        });
        reopened.get(8, TimeUnit.SECONDS);
        assertEquals(first, edt(() -> window.editorTabs().active().orElseThrow().session().path()));
        assertTrue(edt(() -> window.editorTabs().active().orElseThrow().textComponent().isEditable()));
        assertFalse(edt(() -> components(window, JLabel.class).stream().anyMatch(label -> "Synced".equals(label.getText()) || "Autosave on".equals(label.getText()))));
        snapshot(window.snapshotComponent(), Path.of("build/ui-snapshots/editor.png"));
    }
    @Test void wordWrapIsGlobalAppliesToNewDocumentsAndPersistsAcrossRestarts() throws Exception {
        Path first = Files.writeString(temporary.resolve("first.txt"), "Long words ".repeat(100));
        Path second = Files.writeString(temporary.resolve("second.txt"), "Another long line ".repeat(100));
        Path profile = temporary.resolve("profile");
        MainWindow window = window(temporary, temporary, profile);
        edt(() -> {
            Action wrap = window.getRootPane().getActionMap().get("view.wordWrap");
            assertTrue(wrap.isEnabled()); assertEquals(false, wrap.getValue(Action.SELECTED_KEY));
            assertEquals("view.wordWrap", window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("alt Z")));
            command(window, "view.wordWrap");
            return null;
        });
        await(() -> edtUnchecked(() -> Boolean.TRUE.equals(window.getRootPane().getActionMap().get("view.wordWrap").getValue(Action.SELECTED_KEY))
                && window.getRootPane().getActionMap().get("view.wordWrap").isEnabled()));
        edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS);
        edt(() -> window.openFile(second)).get(8, TimeUnit.SECONDS);
        edt(() -> {
            assertTrue(window.editorTabs().views().stream().allMatch(view -> view.textEditor().wordWrap()));
            JMenu view = menu(window, "View");
            JCheckBoxMenuItem wrap = java.util.Arrays.stream(view.getMenuComponents()).filter(JCheckBoxMenuItem.class::isInstance)
                    .map(JCheckBoxMenuItem.class::cast).filter(item -> "Word Wrap".equals(item.getText())).findFirst().orElseThrow();
            assertTrue(wrap.isSelected()); wrap.doClick();
            return null;
        });
        await(() -> edtUnchecked(() -> window.editorTabs().views().stream().noneMatch(view -> view.textEditor().wordWrap())
                && window.getRootPane().getActionMap().get("view.wordWrap").isEnabled()));
        assertFalse(new PropertiesSettingsStore(profile).load().settings().softWrap());
        edt(() -> { command(window, "view.wordWrap"); return null; });
        await(() -> edtUnchecked(() -> window.editorTabs().views().stream().allMatch(view -> view.textEditor().wordWrap())
                && window.getRootPane().getActionMap().get("view.wordWrap").isEnabled()));
        edt(window::finishSnapshot).get(8, TimeUnit.SECONDS);
        MainWindow reopened = window(temporary, temporary, profile);
        assertEquals(2, edt(() -> reopened.editorTabs().views().size()));
        assertTrue(edt(() -> reopened.editorTabs().views().stream().allMatch(view -> view.textEditor().wordWrap())));
        assertEquals(true, edt(() -> reopened.getRootPane().getActionMap().get("view.wordWrap").getValue(Action.SELECTED_KEY)));
        assertTrue(new PropertiesSettingsStore(profile).load().settings().softWrap());
        assertEquals("Long words ".repeat(100), Files.readString(first));
    }
    @Test void createsRealHostProjectAndFileThroughDialogsAndDeletionDoesNotRecreateIt() throws Exception {
        Path hostArea = Files.createDirectories(Path.of("build/gui-smoke").toAbsolutePath()); Path browse = Files.createTempDirectory(hostArea, "project-");
        MainWindow window = window(browse, null, browse.resolve("profile"));
        SwingUtilities.invokeLater(window::createProject);
        JFileChooser chooser = awaitComponent(JFileChooser.class, component -> true);
        edt(() -> { chooser.setSelectedFile(browse.toFile()); chooser.approveSelection(); return null; });
        answerInput("New project", "Created Project"); Path project = browse.resolve("Created Project");
        await(() -> edtUnchecked(() -> window.currentWorkspace() != null && window.currentWorkspace().root().equals(project)));
        SwingUtilities.invokeLater(() -> components(window.projectTree().contextMenuForSelection(), JMenuItem.class).stream()
                .filter(item -> "New file…".equals(item.getText())).findFirst().orElseThrow().doClick());
        answerInput("New file", "note.txt"); Path file = project.resolve("note.txt");
        await(() -> edtUnchecked(() -> window.editorTabs().active().isPresent()));
        edt(() -> { window.editorTabs().active().orElseThrow().textComponent().append("Created in the real GUI · Привет\n"); return null; });
        await(() -> { try { return Files.readString(file).contains("Привет"); } catch (Exception error) { return false; } });
        Files.writeString(project.resolve("host-check.txt"), Files.readString(file));
        edt(() -> window.projectTree().select(file)).get(8, TimeUnit.SECONDS);
        SwingUtilities.invokeLater(() -> {
            JPopupMenu menu = window.projectTree().contextMenuForSelection();
            for (Component item : menu.getComponents()) if (item instanceof JMenuItem action && "Delete…".equals(action.getText())) action.doClick();
        });
        answerConfirm("Delete");
        await(() -> !Files.exists(file) && edtUnchecked(() -> window.editorTabs().views().isEmpty()));
        Thread.sleep(650); assertFalse(Files.exists(file)); assertFalse(Files.exists(browse.resolve("profile/trash")));
        assertEquals("Created in the real GUI · Привет\n", Files.readString(project.resolve("host-check.txt")));
    }
    @Test void damagedRecoveryShowsWarningsWithoutBlockingHealthyDraftsOrFiles() throws Exception {
        Path profile = Files.createDirectories(temporary.resolve("profile"));
        Path drafts = Files.createDirectories(profile.resolve("drafts"));
        Path damaged = Files.write(drafts.resolve(java.util.UUID.randomUUID() + ".draft"), new byte[] {1, 2});
        var storage = new FileDraftRepository(drafts);
        var healthy = new dev.rilvune.document.domain.RecoveryDraft(java.util.UUID.randomUUID(), null, "healthy recovered text", 1,
                dev.rilvune.document.domain.TextFormat.utf8Lf(), null, null, dev.rilvune.document.domain.SyncState.DRAFT, null);
        storage.save(healthy); storage.release(healthy.id());
        Path file = Files.writeString(temporary.resolve("file.txt"), "normal source");
        MainWindow window = window(temporary, temporary, profile);
        JButton warnings = awaitComponent(JButton.class, button -> "recoveryWarnings".equals(button.getName()));
        assertTrue(edt(warnings::isVisible));
        edt(() -> window.openFile(file)).get(5, TimeUnit.SECONDS);
        assertEquals("normal source", edt(() -> window.editorTabs().active().orElseThrow().session().text()));
        assertTrue(edt(warnings::isVisible), "An ordinary status update must not hide the recovery warning");
        SwingUtilities.invokeLater(warnings::doClick);
        JDialog dialog = awaitComponent(JDialog.class, candidate -> "Drafts & recovery".equals(candidate.getTitle()));
        edt(() -> {
            JTextArea details = components(dialog, JTextArea.class).stream().filter(area -> "recoveryProblems".equals(area.getName())).findFirst().orElseThrow();
            assertTrue(details.getText().contains(damaged.toString()));
            assertFalse(details.isEditable());
            components(dialog, JOptionPane.class).getFirst().setValue("Open");
            return null;
        });
        await(() -> edtUnchecked(() -> window.editorTabs().active().orElseThrow().session().id().equals(healthy.id())));
        assertEquals("healthy recovered text", edt(() -> window.editorTabs().active().orElseThrow().session().text()));
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(damaged));
        Files.delete(damaged); // Simulate the user repairing/removing the bad artifact outside the application.
        SwingUtilities.invokeLater(warnings::doClick);
        JDialog refreshed = awaitComponent(JDialog.class, candidate -> "Drafts & recovery".equals(candidate.getTitle()));
        assertFalse(edt(warnings::isVisible));
        edt(() -> { components(refreshed, JOptionPane.class).getFirst().setValue("Cancel"); return null; });
    }

    @Test void restartRestoresActiveTabCaretAndRecentProject() throws Exception {
        Path first = Files.writeString(temporary.resolve("first.txt"), "one\ntwo\n"); Path second = Files.writeString(temporary.resolve("second.txt"), "another\n"); Path profile = temporary.resolve("profile");
        MainWindow window = window(temporary, temporary, profile);
        edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS); edt(() -> window.openFile(second)).get(8, TimeUnit.SECONDS); edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS);
        edt(() -> { window.editorTabs().active().orElseThrow().caret(5); return null; }); edt(window::finishSnapshot).get(8, TimeUnit.SECONDS);
        MainWindow reopened = window(temporary, temporary, profile);
        assertEquals(2, edt(() -> reopened.editorTabs().views().size()));
        assertEquals(first, edt(() -> reopened.editorTabs().active().orElseThrow().session().path())); assertEquals(5, edt(() -> reopened.editorTabs().active().orElseThrow().textComponent().getCaretPosition()));
        assertEquals(List.of(temporary), new SessionRepository(profile).recentProjects());
        JMenu file = edt(() -> reopened.getJMenuBar().getMenu(0));
        assertTrue(edt(() -> { for (Component item : file.getMenuComponents()) if (item instanceof JMenu menu && "Recent Projects".equals(menu.getText())) return menu.getItemCount() > 0; return false; }));
        SwingUtilities.invokeLater(() -> command(reopened, "about"));
        JDialog about = awaitComponent(JDialog.class, dialog -> "About Rilvune".equals(dialog.getTitle()));
        assertTrue(edt(() -> components(about, JLabel.class).stream().anyMatch(label -> "Rilvune  0.2.0".equals(label.getText()))));
        assertTrue(edt(() -> components(about, JTextArea.class).getFirst().getText().contains(profile.toString()))); edt(() -> { about.dispose(); return null; });
    }
    @Test void searchAndReplaceShowPreviewBelowResultsAndPersistReplacement() throws Exception {
        Path first = Files.writeString(temporary.resolve("first.txt"), "first needle line\nsecond needle line\n"); Path second = Files.writeString(temporary.resolve("second.txt"), "other needle\n");
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        SwingUtilities.invokeLater(() -> command(window, "navigate.replace"));
        JTextField query = awaitComponent(JTextField.class, field -> "projectSearchQuery".equals(field.getName()));
        edt(() -> { query.setText("needle"); return null; });
        JList<?> results = awaitComponent(JList.class, list -> "projectSearchResults".equals(list.getName()));
        await(() -> edtUnchecked(() -> results.getModel().getSize() == 3));
        JTextArea preview = awaitComponent(JTextArea.class, area -> "projectSearchPreview".equals(area.getName()));
        await(() -> edtUnchecked(() -> preview.getText().contains("needle")));
        JTextField replacement = awaitComponent(JTextField.class, field -> "projectReplacement".equals(field.getName())); edt(() -> { replacement.setText("updated"); return null; });
        JTextArea after = awaitComponent(JTextArea.class, area -> "projectReplacementPreview".equals(area.getName())); await(() -> edtUnchecked(() -> after.getText().contains("updated")));
        assertTrue(edt(() -> SwingUtilities.convertPoint(preview, 0, 0, query.getTopLevelAncestor()).y > SwingUtilities.convertPoint(results, 0, 0, query.getTopLevelAncestor()).y));
        snapshot(((JDialog) SwingUtilities.getWindowAncestor(query)).getRootPane(), Path.of("build/ui-snapshots/search-replace.png"));
        JButton all = awaitComponent(JButton.class, button -> "Replace all…".equals(button.getText())); SwingUtilities.invokeLater(all::doClick); answerConfirm("Replace in files");
        await(() -> { try { return Files.readString(first).equals("first updated line\nsecond updated line\n") && Files.readString(second).equals("other updated\n"); } catch (Exception error) { return false; } });
    }

    @Test void comparisonOpenedDuringReplacementConfirmationRemainsEditableAndSharesUndo() throws Exception {
        Path first = Files.writeString(temporary.resolve("a.txt"), "needle\n");
        Path second = Files.writeString(temporary.resolve("b.txt"), "needle other\n");
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        SwingUtilities.invokeLater(() -> command(window, "navigate.replace"));
        JTextField query = awaitComponent(JTextField.class, field -> "projectSearchQuery".equals(field.getName()));
        edt(() -> { query.setText("needle"); return null; });
        JList<?> results = awaitComponent(JList.class, list -> "projectSearchResults".equals(list.getName()));
        await(() -> edtUnchecked(() -> results.getModel().getSize() == 2));
        JTextField replacement = awaitComponent(JTextField.class, field -> "projectReplacement".equals(field.getName()));
        edt(() -> { replacement.setText("updated"); return null; });
        JButton all = awaitComponent(JButton.class, button -> "Replace all…".equals(button.getText()));
        SwingUtilities.invokeLater(all::doClick);
        awaitComponent(JDialog.class, dialog -> "Replace in files".equals(dialog.getTitle()));
        var comparison = edt(() -> window.openComparison(first, second)).get(8, TimeUnit.SECONDS);
        answerConfirm("Replace in files");
        await(() -> edtUnchecked(query::isEnabled)); // Application AND ownership cleanup finished.
        edt(() -> {
            assertFalse(comparison.leftView().session().isClosed());
            assertFalse(comparison.rightView().session().isClosed());
            assertEquals("updated\n", comparison.leftView().textComponent().getText());
            assertEquals("updated other\n", comparison.rightView().textComponent().getText());
            ((JDialog) SwingUtilities.getWindowAncestor(query)).dispose();
            comparison.leftView().textEditor().undo();
            assertEquals("needle\n", comparison.leftView().textComponent().getText());
            return null;
        });
        comparison.leftView().session().flush().get(5, TimeUnit.SECONDS);
        assertEquals("needle\n", Files.readString(first));
    }

    @Test void projectTransitionDisposesConfirmationAndDrainsItsReplacementPlan() throws Exception {
        Path first = Files.writeString(temporary.resolve("a.txt"), "needle\n");
        Path next = Files.createDirectory(temporary.resolve("next"));
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        SwingUtilities.invokeLater(() -> command(window, "navigate.replace"));
        JTextField query = awaitComponent(JTextField.class, field -> "projectSearchQuery".equals(field.getName()));
        edt(() -> { query.setText("needle"); return null; });
        JList<?> results = awaitComponent(JList.class, list -> "projectSearchResults".equals(list.getName()));
        await(() -> edtUnchecked(() -> results.getModel().getSize() == 1));
        JButton all = awaitComponent(JButton.class, button -> "Replace all…".equals(button.getText()));
        SwingUtilities.invokeLater(all::doClick);
        JDialog confirmation = awaitComponent(JDialog.class, dialog -> "Replace in files".equals(dialog.getTitle()));
        edt(() -> window.openProject(next)).get(8, TimeUnit.SECONDS);
        assertEquals(next, edt(() -> window.currentWorkspace().root()));
        assertFalse(edt(confirmation::isDisplayable));
        assertFalse(edt(() -> query.getTopLevelAncestor().isDisplayable()));
        assertEquals("needle\n", Files.readString(first));
        assertTrue(edt(() -> window.editorTabs().tabs().isEmpty()));
        edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS);
        assertFalse(edt(() -> window.editorTabs().active().orElseThrow().session().isClosed()));
    }

    @Test void pastingACopyBesideItsSourceAutomaticallyNamesAndSelectsItWithoutAPrompt() throws Exception {
        Path source = Files.writeString(temporary.resolve("notes.txt"), "original\n");
        Path occupied = Files.writeString(temporary.resolve("notes (copy).txt"), "do not replace\n");
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        edt(() -> window.openFile(source)).get(8, TimeUnit.SECONDS);
        edt(() -> { window.editorTabs().active().orElseThrow().textComponent().append("latest edit\n"); return null; });
        edt(() -> window.projectTree().select(source)).get(8, TimeUnit.SECONDS);
        edt(() -> {
            components(window.projectTree().contextMenuForSelection(), JMenuItem.class).stream()
                    .filter(item -> "Copy".equals(item.getText())).findFirst().orElseThrow().doClick(); return null;
        });
        edt(() -> window.projectTree().select(temporary)).get(8, TimeUnit.SECONDS);
        for (int number : new int[]{2, 3}) {
            Path expected = temporary.resolve("notes (copy " + number + ").txt");
            SwingUtilities.invokeLater(() -> components(window.projectTree().contextMenuForSelection(), JMenuItem.class).stream()
                    .filter(item -> "Paste".equals(item.getText())).findFirst().orElseThrow().doClick());
            await(() -> Files.exists(expected) && edtUnchecked(() -> window.projectTree().selectedPath().filter(expected::equals).isPresent()));
            assertEquals("original\nlatest edit\n", Files.readString(expected));
        }
        assertEquals("original\nlatest edit\n", Files.readString(source));
        assertEquals("do not replace\n", Files.readString(occupied));
        assertFalse(edt(() -> java.util.Arrays.stream(Window.getWindows()).anyMatch(item -> item instanceof JDialog && item.isShowing())));
    }
    @Test void projectDocumentConsumersAreDrainedAndCannotUseAReopenedProjectActivation() throws Exception {
        Path file = Files.writeString(temporary.resolve("scoped.txt"), "base");
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        edt(() -> window.openFile(file)).get(8, TimeUnit.SECONDS);
        var oldContext = edt(window::projectContext);
        var consumer = window.projectDocuments().open(oldContext, Path.of("scoped.txt")).get(8, TimeUnit.SECONDS);
        var version = consumer.snapshot().version();
        assertTrue(consumer.apply(version, "scoped change").get(8, TimeUnit.SECONDS).accepted());
        edt(() -> { command(window, "project.close"); return null; });
        await(() -> edtUnchecked(() -> window.currentWorkspace() == null));
        assertTrue(documentServices.get(window).sessions().isEmpty());
        assertEquals(dev.rilvune.workspace.application.ProjectDocuments.Rejection.STALE_PROJECT,
                consumer.apply(version, "late").get(8, TimeUnit.SECONDS).rejection());
        edt(() -> window.openProject(temporary)).get(8, TimeUnit.SECONDS);
        assertNotEquals(oldContext, edt(window::projectContext));
        assertEquals("scoped change", Files.readString(file));
    }

    @Test void recoveredPauseIsVisibleAndReviewDoesNotOverwriteAnExternalChange() throws Exception {
        Path file = Files.writeString(temporary.resolve("paused.txt"), "base");
        Path profile = Files.createDirectories(temporary.resolve("profile"));
        var disk = new NioDocumentStore(10000).read(file);
        var draft = new dev.rilvune.document.domain.RecoveryDraft(java.util.UUID.randomUUID(), file, "local", 1,
                disk.format(), disk, disk, dev.rilvune.document.domain.SyncState.PAUSED, null);
        var storage = new FileDraftRepository(profile.resolve("drafts")); storage.save(draft); storage.release(draft.id());
        MainWindow window = window(temporary, temporary, profile);
        JButton review = awaitComponent(JButton.class, button -> "Review and resume".equals(button.getText()));
        assertEquals("base", Files.readString(file));
        Files.writeString(file, "outside");
        edt(() -> { review.doClick(); return null; });
        await(() -> edtUnchecked(() -> window.editorTabs().active().orElseThrow().session().state() == dev.rilvune.document.domain.SyncState.CONFLICT));
        assertEquals("local", edt(() -> window.editorTabs().active().orElseThrow().session().text()));
        assertEquals("outside", Files.readString(file));
    }

    @Test void concurrentOpensOfOneFileTransferOnlyOneTabConsumer() throws Exception {
        Path file = Files.writeString(temporary.resolve("duplicate.txt"), "source");
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"));
        edt(() -> CompletableFuture.allOf(window.openFile(file), window.openFile(file))).get(8, TimeUnit.SECONDS);
        assertEquals(1, edt(() -> window.editorTabs().views().size()));
        assertEquals(1, documentServices.get(window).sessions().size());
        assertTrue(edt(() -> window.editorTabs().closeAll()).get(8, TimeUnit.SECONDS));
        assertTrue(documentServices.get(window).sessions().isEmpty());
    }

    @Test void delayedTextReopenCannotOverrideAMoreRecentFileRequest() throws Exception {
        Path first = Files.writeString(temporary.resolve("closing.txt"), "first");
        Path second = Files.writeString(temporary.resolve("newer.txt"), "second");
        ExecutorService io = Executors.newFixedThreadPool(2);
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"), io);
        edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS);
        var session = edt(() -> window.editorTabs().activeDocument().orElseThrow());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            io.execute(() -> { entered.countDown(); try { release.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = edt(session::close); assertFalse(closing.isDone());
            var earlier = edt(() -> window.openFile(first));
            var newer = edt(() -> window.openFile(second));
            release.countDown(); CompletableFuture.allOf(earlier, newer).get(8, TimeUnit.SECONDS);
            assertEquals(second, edt(() -> window.editorTabs().activeFilePath().orElseThrow()));
            assertEquals(2, edt(() -> window.editorTabs().tabs().size()), "The obsolete request must not create another tab");
            edt(() -> window.openFile(first)).get(8, TimeUnit.SECONDS);
            assertEquals(first, edt(() -> window.editorTabs().activeFilePath().orElseThrow()));
            assertEquals(2, edt(() -> window.editorTabs().tabs().size()), "Reopening must replace the closed view");
        } finally { release.countDown(); }
    }

    @Test void switchingProjectDrainsASuccessfulDelayedOpenWithoutAnInvisibleSession() throws Exception {
        Path file = Files.writeString(temporary.resolve("late.txt"), "source");
        Path next = Files.createDirectory(temporary.resolve("next"));
        ExecutorService io = Executors.newFixedThreadPool(2);
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"), io);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            io.execute(() -> { entered.countDown(); try { release.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> opening = edt(() -> window.openFile(file));
            CompletableFuture<Void> transition = edt(() -> window.openProject(next));
            assertFalse(transition.isDone());
            release.countDown(); opening.get(8, TimeUnit.SECONDS); transition.get(8, TimeUnit.SECONDS);
            assertEquals(next, edt(() -> window.currentWorkspace().root()));
            assertTrue(edt(() -> window.editorTabs().views().isEmpty()));
            assertTrue(documentServices.get(window).sessions().isEmpty());
            assertEquals("source", Files.readString(file));
            edt(() -> window.openFile(file)).get(8, TimeUnit.SECONDS);
            assertEquals(file, edt(() -> window.editorTabs().active().orElseThrow().session().path()));
        } finally { release.countDown(); }
    }

    @Test void shutdownDrainsASuccessfulDelayedOpenBeforeClosingTheDocumentService() throws Exception {
        Path file = Files.writeString(temporary.resolve("late-exit.txt"), "source");
        ExecutorService io = Executors.newFixedThreadPool(2);
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"), io);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            io.execute(() -> { entered.countDown(); try { release.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var opening = edt(() -> window.openFile(file));
            var shutdown = edt(window::finishSnapshot); assertFalse(shutdown.isDone());
            release.countDown(); opening.get(8, TimeUnit.SECONDS); shutdown.get(8, TimeUnit.SECONDS);
            assertTrue(documentServices.get(window).sessions().isEmpty());
            assertFalse(edt(window::isDisplayable)); assertEquals("source", Files.readString(file));
        } finally { release.countDown(); }
    }

    @Test void switchingProjectInvalidatesPendingFileOpenErrors() throws Exception {
        Path next = Files.createDirectory(temporary.resolve("next"));
        ExecutorService io = Executors.newFixedThreadPool(2); // One lane is occupied by the filesystem watcher.
        MainWindow window = window(temporary, temporary, temporary.resolve("profile"), io);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean obsoleteDialog = new java.util.concurrent.atomic.AtomicBoolean();
        AWTEventListener listener = event -> {
            if (event instanceof WindowEvent opened && opened.getID() == WindowEvent.WINDOW_OPENED
                    && opened.getWindow() instanceof JDialog dialog && "Open file".equals(dialog.getTitle())) {
                obsoleteDialog.set(true); SwingUtilities.invokeLater(dialog::dispose);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        try {
            io.execute(() -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> obsolete = edt(() -> window.openFile(temporary.resolve("missing.txt")));
            CompletableFuture<Void> switched = edt(() -> window.openProject(next));
            release.countDown();
            assertThrows(ExecutionException.class, () -> obsolete.get(8, TimeUnit.SECONDS));
            switched.get(8, TimeUnit.SECONDS);
            assertEquals(next, edt(() -> window.currentWorkspace().root()));
            assertFalse(obsoleteDialog.get(), "An obsolete request must not open a modal error in the new project");
        } finally { release.countDown(); Toolkit.getDefaultToolkit().removeAWTEventListener(listener); }
    }

    @Test void localChangesUsesSnapshotsAndFollowsEditorAutosaveWithoutOpeningDocuments() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("note.txt", "base\n"); fixture.commit("Root"); fixture.write("note.txt", "working\n");
        MainWindow window = window(temporary, fixture.root, temporary.resolve("profile"));
        await(() -> edtUnchecked(() -> window.getRootPane().getActionMap().get("view.git").isEnabled()));
        edt(() -> { JMenuItem git = java.util.Arrays.stream(menu(window, "View").getMenuComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast).filter(item -> "Git History".equals(item.getText())).findFirst().orElseThrow(); assertTrue(git.isEnabled()); return null; });
        assertTrue(edt(() -> window.editorTabs().views().isEmpty())); edt(() -> { command(window, "view.gitChanges"); return null; });
        JTextArea gitText = awaitComponent(JTextArea.class, area -> "git.diff.right".equals(area.getName()));
        await(() -> edtUnchecked(() -> gitText.getText().equals("working\n"))); assertTrue(edt(() -> window.editorTabs().views().isEmpty()));
        edt(() -> window.openFile(fixture.root.resolve("note.txt"))).get(8, TimeUnit.SECONDS);
        edt(() -> { window.editorTabs().active().orElseThrow().textComponent().append("autosaved\n"); return null; });
        try { await(() -> edtUnchecked(() -> gitText.getText().contains("autosaved"))); }
        catch (AssertionError failure) { throw new AssertionError(edt(() -> components((Container) SwingUtilities.getWindowAncestor(gitText), JLabel.class).stream().map(JLabel::getText).toList()).toString() + " · disk=" + Files.readString(fixture.root.resolve("note.txt")), failure); }
        edt(() -> { gitText.getDocument().insertString(0, "BAD", null); assertFalse(gitText.getText().contains("BAD")); return null; });
        assertEquals("working\nautosaved\n", Files.readString(fixture.root.resolve("note.txt"))); assertEquals(1, edt(() -> window.editorTabs().views().size()));
        Path plain = Files.createDirectory(temporary.resolve("plain")); edt(() -> window.openProject(plain)).get(8, TimeUnit.SECONDS); await(() -> edtUnchecked(() -> gitText.getText().isEmpty()));
        edt(() -> { assertFalse(window.getRootPane().getActionMap().get("view.git").isEnabled()); JMenuItem git = java.util.Arrays.stream(menu(window, "View").getMenuComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast).filter(item -> "Git History".equals(item.getText())).findFirst().orElseThrow(); assertFalse(git.isEnabled()); return null; });
    }

    @Test void projectTreeUsesIntelliJColorsAndUpdatesWithGitHidden() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("PREVIEW · Project colors"));
        fixture.write("unchanged.txt", "base\n"); fixture.write("changed.txt", "base\n"); fixture.write("source/nested.txt", "base\n"); fixture.write("build/tracked.txt", "base\n"); fixture.write(".gitignore", "*.log\n"); fixture.commit("Initial files");
        fixture.write("changed.txt", "changed\n"); fixture.write("build/tracked.txt", "changed\n"); fixture.write("new.txt", "new\n"); fixture.write("added.txt", "added\n"); fixture.git("add", "added.txt"); fixture.write("ignored.log", "ignored\n"); fixture.write("<html><b>note.txt", "literal filename\n");
        MainWindow window = window(temporary, fixture.root, temporary.resolve("profile"));
        await(() -> edtUnchecked(() -> new Color(0x6897BB).equals(projectColor(window, "changed.txt", false)) && new Color(0x747982).equals(projectColor(window, "ignored.log", false))));
        edt(() -> {
            assertEquals(new Color(0xBCBEC4), projectColor(window, "unchanged.txt", false));
            assertEquals(new Color(0xC18372), projectColor(window, "new.txt", false));
            assertEquals(new Color(0x77A768), projectColor(window, "added.txt", false));
            assertEquals(new Color(0x85B5EA), projectColor(window, "changed.txt", true));
            JLabel literal = projectLabel(window, "<html><b>note.txt", false); assertEquals("<html><b>note.txt", literal.getText()); assertNull(literal.getClientProperty("html"));
            assertTrue(projectLabel(window, "changed.txt", false).getToolTipText().contains("Modified"));
            assertTrue(window.editorTabs().views().isEmpty());
            assertTrue(java.util.Arrays.stream(Window.getWindows()).filter(frame -> frame instanceof JFrame && ((JFrame)frame).getTitle().startsWith("Git —")).noneMatch(Window::isVisible)); return null;
        });
        edt(() -> window.projectTree().select(fixture.root.resolve("unchanged.txt"))).get(5, TimeUnit.SECONDS);
        snapshot(window.projectTree().component(), Path.of("build/ui-snapshots/project-colors.png"));
        fixture.git("restore", "--", "changed.txt");
        await(() -> edtUnchecked(() -> new Color(0xBCBEC4).equals(projectColor(window, "changed.txt", false))));
        assertEquals(fixture.root.resolve("unchanged.txt"), edt(() -> window.projectTree().selectedPath().orElseThrow()));
        fixture.write("source/nested.txt", "external update\n");
        edt(() -> window.projectTree().select(fixture.root.resolve("source/nested.txt"))).get(5, TimeUnit.SECONDS);
        await(() -> edtUnchecked(() -> new Color(0x6897BB).equals(projectColor(window, "nested.txt", false))));
        edt(() -> { window.projectTree().setShowExcludedDirectories(true); return null; });
        edt(() -> window.projectTree().select(fixture.root.resolve("build/tracked.txt"))).get(5, TimeUnit.SECONDS);
        await(() -> edtUnchecked(() -> new Color(0x6897BB).equals(projectColor(window, "tracked.txt", false))));
        edt(() -> window.openFile(fixture.root.resolve("unchanged.txt"))).get(5, TimeUnit.SECONDS);
        edt(() -> { window.editorTabs().active().orElseThrow().textComponent().append("autosaved\n"); return null; });
        await(() -> edtUnchecked(() -> new Color(0x6897BB).equals(projectColor(window, "unchanged.txt", false))));
        fixture.git("commit", "-am", "External commit");
        await(() -> edtUnchecked(() -> new Color(0xBCBEC4).equals(projectColor(window, "added.txt", false)) && new Color(0xBCBEC4).equals(projectColor(window, "unchanged.txt", false))));
        fixture.write(".gitignore", "*.log\n!ignored.log\n");
        await(() -> edtUnchecked(() -> new Color(0xC18372).equals(projectColor(window, "ignored.log", false))));
        Path metadata = temporary.resolve("saved-git-metadata"); Files.move(fixture.root.resolve(".git"), metadata);
        await(() -> edtUnchecked(() -> new Color(0xBCBEC4).equals(projectColor(window, "new.txt", false)) && !window.getRootPane().getActionMap().get("view.git").isEnabled()));
        Files.move(metadata, fixture.root.resolve(".git"));
        await(() -> edtUnchecked(() -> new Color(0xC18372).equals(projectColor(window, "new.txt", false)) && window.getRootPane().getActionMap().get("view.git").isEnabled()));
    }
    @Test void editorTabGitColorsUpdateWithoutGitWindowIncludingFilesOutsideMaterializedTree() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("Tab colors"));
        fixture.write("changed.txt", "base\n"); fixture.write("clean.txt", "base\n"); fixture.write(".gitignore", "*.log\n"); fixture.commit("Initial");
        fixture.write("changed.txt", "modified\n"); fixture.write("new.txt", "new\n"); fixture.write("added.txt", "added\n"); fixture.git("add", "added.txt");
        fixture.write("collapsed/ignored.log", "ignored\n"); fixture.write("<html><b>literal.txt", "literal\n");
        MainWindow window = window(temporary, fixture.root, temporary.resolve("profile"));
        for (String file : List.of("changed.txt", "new.txt", "added.txt", "collapsed/ignored.log", "<html><b>literal.txt", "clean.txt"))
            edt(() -> window.openFile(fixture.root.resolve(file))).get(5, TimeUnit.SECONDS);
        await(() -> edtUnchecked(() -> new Color(0x6897BB).equals(tabLabel(window, 0).getForeground())
                && new Color(0x747982).equals(tabLabel(window, 3).getForeground())));
        edt(() -> {
            assertEquals(new Color(0xC18372), tabLabel(window, 1).getForeground());
            assertEquals(new Color(0x77A768), tabLabel(window, 2).getForeground());
            assertEquals(new Color(0xBCBEC4), tabLabel(window, 5).getForeground());
            assertTrue(tabLabel(window, 0).getToolTipText().contains("Modified"));
            assertNull(tabLabel(window, 4).getClientProperty("html"));
            window.editorTabs().select(window.editorTabs().views().getFirst().session().id());
            assertEquals(new Color(0x85B5EA), tabLabel(window, 0).getForeground()); return null;
        });
        snapshot(window.snapshotComponent(), Path.of("build/ui-snapshots/tab-colors.png"));
        fixture.git("restore", "--", "changed.txt");
        await(() -> edtUnchecked(() -> new Color(0xBCBEC4).equals(tabLabel(window, 0).getForeground())));
        fixture.git("add", "new.txt");
        await(() -> edtUnchecked(() -> new Color(0x77A768).equals(tabLabel(window, 1).getForeground())));
        fixture.write(".gitignore", "*.log\n!collapsed/ignored.log\n");
        await(() -> edtUnchecked(() -> new Color(0xC18372).equals(tabLabel(window, 3).getForeground())));
        edt(() -> { window.editorTabs().active().orElseThrow().textComponent().append("autosaved\n"); return null; });
        await(() -> edtUnchecked(() -> new Color(0x85B5EA).equals(tabLabel(window, 0).getForeground())));
        Path metadata = temporary.resolve("tab-repository-metadata"); Files.move(fixture.root.resolve(".git"), metadata);
        await(() -> edtUnchecked(() -> new Color(0xBCBEC4).equals(tabLabel(window, 1).getForeground())));
        edt(() -> { command(window, "file.new"); return null; });
        await(() -> edtUnchecked(() -> window.editorTabs().active().filter(view -> view.session().path() == null).isPresent()));
        edt(() -> { assertEquals(new Color(0xBCBEC4), tabLabel(window, 6).getForeground()); return null; });
    }
    @Test void editorGitHandlesFormatOnlyChangesEmptyDeletionsAndNewFiles() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("Editor edge cases")); fixture.write("format.txt", "\ufeffbase\r\n"); fixture.write("empty.txt", "removed\n"); fixture.write("committed-empty.txt", ""); fixture.commit("Initial formats");
        fixture.write("format.txt", "base\n"); fixture.write("empty.txt", ""); fixture.write("fresh.txt", "new source\n");
        MainWindow window = window(temporary, fixture.root, temporary.resolve("profile"));
        edt(() -> window.openFile(fixture.root.resolve("format.txt"))).get(5, TimeUnit.SECONDS);
        EditorView format = edt(() -> window.editorTabs().active().orElseThrow());
        await(() -> edtUnchecked(() -> format.textEditor().gutter().presentation().message().contains("File format differs")));
        assertTrue(edt(() -> format.textEditor().gutter().presentation().markers().isEmpty()));
        edt(() -> window.openFile(fixture.root.resolve("empty.txt"))).get(5, TimeUnit.SECONDS);
        EditorView empty = edt(() -> window.editorTabs().active().orElseThrow());
        await(() -> edtUnchecked(() -> empty.textEditor().gutter().presentation().markers().size() == 1));
        edt(() -> {
            var marker = empty.textEditor().gutter().presentation().markers().getFirst(); assertEquals(0, marker.startOffset()); assertEquals(0, marker.endOffset());
            empty.textComponent().getActionMap().get("git.editor.preview").actionPerformed(new ActionEvent(empty, 0, "preview")); return null;
        });
        JButton revert = awaitComponent(JButton.class, button -> button.isShowing() && "git.change.revert".equals(button.getName()));
        edt(() -> { revert.doClick(); return null; });
        await(() -> { try { return Files.readString(fixture.root.resolve("empty.txt")).equals("removed\n"); } catch (Exception error) { return false; } });
        edt(() -> window.openFile(fixture.root.resolve("fresh.txt"))).get(5, TimeUnit.SECONDS);
        EditorView fresh = edt(() -> window.editorTabs().active().orElseThrow());
        await(() -> edtUnchecked(() -> fresh.textEditor().gutter().presentation().message().contains("unavailable")));
        assertTrue(edt(() -> fresh.textEditor().gutter().presentation().markers().isEmpty()));
        assertTrue(edt(() -> fresh.textEditor().gutter().presentation().annotations().isEmpty()));
        fixture.git("add", "fresh.txt");
        await(() -> edtUnchecked(() -> fresh.textEditor().gutter().presentation().markers().size() == 1));
        assertEquals(dev.rilvune.platform.theme.GutterColors.ADDED, edt(() -> fresh.textEditor().gutter().presentation().markers().getFirst().color()));
        edt(() -> window.openFile(fixture.root.resolve("committed-empty.txt"))).get(5, TimeUnit.SECONDS);
        var committedEmpty = edt(() -> window.editorTabs().active().orElseThrow().textEditor().gutter());
        await(() -> edtUnchecked(() -> committedEmpty.presentation().message().equals("No source changes relative to HEAD")));
        edt(() -> { committedEmpty.dispatchEvent(new MouseEvent(committedEmpty, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 20, 1, true, MouseEvent.BUTTON3)); return null; });
        JCheckBoxMenuItem annotate = awaitComponent(JCheckBoxMenuItem.class, item -> item.isShowing() && "git.editor.annotate".equals(item.getName()));
        edt(() -> { assertTrue(annotate.isEnabled()); annotate.doClick(); MenuSelectionManager.defaultManager().clearSelectedPath(); return null; });
        await(() -> edtUnchecked(() -> committedEmpty.presentation().message().equals("No committed lines")));
        edt(() -> window.openFile(fixture.root.resolve(".git/config"))).get(5, TimeUnit.SECONDS);
        await(() -> edtUnchecked(() -> window.editorTabs().active().orElseThrow().textEditor().gutter().presentation().message().contains("unavailable")));
        assertTrue(edt(() -> window.editorTabs().active().orElseThrow().textEditor().gutter().presentation().markers().isEmpty()));
    }

    @Test void editorGitMarkersBlamePreviewsRevertUndoAndCommitNavigation() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("Rilvune Git"));
        String original = "one\nkeep\nbefore\nkeep two\nremoved\nlast\n";
        String working = "one\nkeep\nAFTER\nkeep two\nlast\nadded\n";
        fixture.write("note.txt", original); String commit = fixture.commit("Initial editor inspection");
        fixture.write("note.txt", working); byte[] index = Files.readAllBytes(fixture.root.resolve(".git/index"));
        MainWindow window = window(temporary, fixture.root, temporary.resolve("profile"));
        edt(() -> window.openFile(fixture.root.resolve("note.txt"))).get(8, TimeUnit.SECONDS);
        EditorView view = edt(() -> window.editorTabs().active().orElseThrow());
        var gutter = edt(() -> view.textEditor().gutter());
        try { await(() -> edtUnchecked(() -> gutter.presentation().markers().size() == 3)); }
        catch (AssertionError error) { throw new AssertionError(edt(() -> "Gutter: " + gutter.presentation() + " document: " + view.session().snapshot().state()), error); }
        edt(() -> {
            assertTrue(gutter.presentation().markers().stream().anyMatch(marker -> marker.startOffset() == marker.endOffset()));
            var marker = gutter.presentation().markers().getFirst(); Rectangle bounds = gutter.markerBounds(marker);
            assertEquals(marker.id(), gutter.markerAt(new Point(bounds.x + 6, bounds.y + 2)).orElseThrow().id());
            gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 20, 1, true, MouseEvent.BUTTON3)); return null;
        });
        JCheckBoxMenuItem annotate = awaitComponent(JCheckBoxMenuItem.class, item -> "git.editor.annotate".equals(item.getName()));
        edt(() -> { annotate.doClick(); MenuSelectionManager.defaultManager().clearSelectedPath(); return null; });
        await(() -> edtUnchecked(() -> gutter.presentation().annotations().stream().anyMatch(annotation -> annotation.navigable() && annotation.description().contains(commit))));
        edt(() -> {
            assertTrue(gutter.presentation().annotations().stream().anyMatch(annotation -> annotation.line() == 2 && annotation.label().isEmpty() && annotation.description().isEmpty() && !annotation.navigable()));
            var changedRow = view.textComponent().modelToView2D(working.indexOf("AFTER"));
            assertNull(gutter.getToolTipText(new MouseEvent(gutter, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 8, (int)changedRow.getY() + 4, 0, false)));
            view.textEditor().applySettings(new TextEditorSettings(4, true, true, false, false, true), EditorTheme.graphite());
            assertTrue(gutter.getPreferredSize().width > 100);
            view.textEditor().applySettings(new TextEditorSettings(4, true, false, true, false, true), EditorTheme.graphite()); return null;
        });
        snapshot(window.snapshotComponent(), Path.of("build/ui-snapshots/rilvune-git-blame.png"));
        edt(() -> {
            var row = view.textComponent().modelToView2D(0);
            gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 8, (int)row.getY() + 6, 1, false, MouseEvent.BUTTON1)); return null;
        });
        JFrame git = awaitComponent(JFrame.class, frame -> frame.getTitle().equals("Git — Rilvune Git"));
        await(() -> edtUnchecked(() -> components(git, JTextArea.class).stream().anyMatch(text -> text.getText().contains("Initial editor inspection"))));
        edt(() -> { git.dispatchEvent(new WindowEvent(git, WindowEvent.WINDOW_CLOSING)); window.toFront(); window.requestFocus(); view.focusText(); return null; });
        await(() -> edtUnchecked(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == view.textComponent()));
        await(() -> edtUnchecked(() -> gutter.presentation().markers().size() == 3));
        edt(() -> { view.caret(working.indexOf("AFTER")); view.textComponent().getActionMap().get("git.editor.preview").actionPerformed(new ActionEvent(view, 0, "preview")); return null; });
        JButton revert;
        try { revert = awaitComponent(JButton.class, button -> button.isShowing() && "git.change.revert".equals(button.getName())); }
        catch (AssertionError error) { snapshot(window.snapshotComponent(), Path.of("build/ui-snapshots/rilvune-git-popup-failure.png")); throw error; }
        edt(() -> {
            JPopupMenu popup = (JPopupMenu)SwingUtilities.getAncestorOfClass(JPopupMenu.class, revert);
            var previews = components(popup, JTextArea.class);
            assertEquals(1, previews.size()); assertEquals("before\n", previews.getFirst().getText());
            assertFalse(previews.getFirst().isEditable());
            assertTrue(previews.getFirst().getHighlighter().getHighlights().length > 0);
            var currentHighlights = view.textComponent().getHighlighter().getHighlights();
            assertTrue(java.util.Arrays.stream(currentHighlights).anyMatch(highlight -> highlight.getStartOffset() == working.indexOf("AFTER") && highlight.getEndOffset() == working.indexOf("AFTER") + 5));
            var row = view.textComponent().modelToView2D(working.indexOf("AFTER"));
            Point bottom = new Point((int)row.getX(), (int)row.getMaxY()); SwingUtilities.convertPointToScreen(bottom, view.textComponent());
            assertTrue(popup.getLocationOnScreen().y >= bottom.y, "Preview must leave current source visible");
            return null;
        });
        new Robot().waitForIdle();
        Rectangle screen = edt(() -> new Rectangle(window.getLocationOnScreen(), window.getSize()));
        javax.imageio.ImageIO.write(new Robot().createScreenCapture(screen), "png", Path.of("build/ui-snapshots/rilvune-git-change.png").toFile());
        edt(() -> { assertTrue(revert.isEnabled()); revert.doClick(); return null; });
        await(() -> { try { return Files.readString(fixture.root.resolve("note.txt")).equals(working.replace("AFTER", "before")); } catch (Exception error) { return false; } });
        assertArrayEquals(index, Files.readAllBytes(fixture.root.resolve(".git/index")));
        assertEquals(0, edt(() -> view.textComponent().getHighlighter().getHighlights().length));
        edt(() -> { view.textEditor().undo(); return null; });
        await(() -> { try { return Files.readString(fixture.root.resolve("note.txt")).equals(working); } catch (Exception error) { return false; } });
        await(() -> edtUnchecked(() -> gutter.presentation().markers().size() == 3));
        edt(() -> { view.caret(working.indexOf("AFTER")); view.textComponent().getActionMap().get("git.editor.preview").actionPerformed(new ActionEvent(view, 0, "preview")); return null; });
        JButton previousPopup = awaitComponent(JButton.class, button -> button.isShowing() && "git.change.revert".equals(button.getName()));
        edt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            view.caret(working.indexOf("added"));
            view.textComponent().getActionMap().get("git.editor.preview").actionPerformed(new ActionEvent(view, 0, "preview")); return null;
        });
        JButton addition = awaitComponent(JButton.class, button -> button.isShowing() && "git.change.revert".equals(button.getName()) && button != previousPopup);
        edt(() -> {
            assertTrue(components((JPopupMenu)SwingUtilities.getAncestorOfClass(JPopupMenu.class, addition), JTextArea.class).isEmpty(), "A pure addition has no previous source to display");
            assertTrue(view.textComponent().getHighlighter().getHighlights().length > 0);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            assertEquals(0, view.textComponent().getHighlighter().getHighlights().length);
            view.caret(working.indexOf("AFTER")); view.textComponent().getActionMap().get("git.editor.preview").actionPerformed(new ActionEvent(view, 0, "preview")); return null;
        });
        JButton stale = awaitComponent(JButton.class, button -> button.isShowing() && "git.change.revert".equals(button.getName()));
        edt(() -> { view.textComponent().append("newer edit\n"); stale.doClick(); return null; });
        await(() -> { try { return Files.readString(fixture.root.resolve("note.txt")).equals(working + "newer edit\n"); } catch (Exception error) { return false; } });
        assertArrayEquals(index, Files.readAllBytes(fixture.root.resolve(".git/index")));
        await(() -> edtUnchecked(() -> gutter.presentation().markers().size() == 3));
        edt(() -> { view.caret(working.indexOf("AFTER")); view.textComponent().getActionMap().get("git.editor.preview").actionPerformed(new ActionEvent(view, 0, "preview")); return null; });
        JButton full = awaitComponent(JButton.class, button -> button.isShowing() && "git.change.full".equals(button.getName()));
        edt(() -> { full.doClick(); return null; });
        JDialog comparison = awaitComponent(JDialog.class, dialog -> dialog.getTitle().equals("Git editor comparison · captured snapshots"));
        edt(() -> { assertTrue(components(comparison, JTextArea.class).stream().allMatch(text -> !text.isEditable())); comparison.dispose(); return null; });
    }

    private static JLabel tabLabel(MainWindow window, int index) {
        JTabbedPane pane = components(window.editorTabs(), JTabbedPane.class).getFirst();
        return components((Container)pane.getTabComponentAt(index), JLabel.class).getFirst();
    }

    private static Color projectColor(MainWindow window, String filename, boolean selected) {
        JLabel label = projectLabel(window, filename, selected); return label == null ? null : label.getForeground();
    }
    private static JLabel projectLabel(MainWindow window, String filename, boolean selected) {
        JTree tree = components(window.projectTree().component(), JTree.class).getFirst();
        var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot(); var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) { var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement(); if (filename.equals(node.toString())) return (JLabel)tree.getCellRenderer().getTreeCellRendererComponent(tree, node, selected, false, node.isLeaf(), 0, false); }
        return null;
    }

    @Test void imageTabsUseCommonWorkspaceWithoutTextSessionsAndRestoreMixedOrder() throws Exception {
        Path text = Files.writeString(temporary.resolve("notes.txt"), "unchanged text\n");
        Path image = temporary.resolve("image-with-misleading-extension.txt");
        BufferedImage pixels = new BufferedImage(900, 650, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = pixels.createGraphics();
        graphics.setColor(new Color(70, 140, 210, 190)); graphics.fillRoundRect(80, 70, 650, 420, 60, 60);
        graphics.setColor(new Color(230, 190, 80)); graphics.fillOval(350, 180, 300, 300); graphics.dispose();
        javax.imageio.ImageIO.write(pixels, "png", image.toFile());
        Path profile = temporary.resolve("profile"); MainWindow window = window(temporary, temporary, profile);
        edt(() -> window.openFile(text)).get(8, TimeUnit.SECONDS);
        SwingUtilities.invokeLater(() -> command(window, "navigate.file"));
        JTextField query = awaitComponent(JTextField.class, field -> "projectSearchQuery".equals(field.getName()));
        edt(() -> { query.setText("image-with-misleading-extension"); return null; });
        JList<?> results = awaitComponent(JList.class, list -> "projectSearchResults".equals(list.getName()));
        await(() -> edtUnchecked(() -> results.getModel().getSize() == 1));
        edt(() -> { query.postActionEvent(); return null; });
        await(() -> edtUnchecked(() -> window.editorTabs().activeTab().filter(dev.rilvune.image.ui.ImageTabView.class::isInstance)
                .map(dev.rilvune.image.ui.ImageTabView.class::cast)
                .filter(view -> view.controller().model().state().phase() == dev.rilvune.image.domain.ImageTabState.Phase.READY).isPresent()));
        String imageId = edt(() -> window.editorTabs().activeId());
        edt(() -> {
            assertTrue(window.editorTabs().activeTextEditor().isEmpty()); assertTrue(window.editorTabs().activeDocument().isEmpty());
            assertFalse(window.getRootPane().getActionMap().get("edit.undo").isEnabled());
            assertFalse(window.getRootPane().getActionMap().get("file.assign").isEnabled());
            assertTrue(window.getRootPane().getActionMap().get("git.fileHistory").isEnabled());
            var view = (dev.rilvune.image.ui.ImageTabView) window.editorTabs().activeTab().orElseThrow();
            assertEquals(dev.rilvune.image.domain.ImageTabState.Phase.READY, view.controller().model().state().phase());
            assertEquals(1, documentServices.get(window).sessions().size());
            components(view, JButton.class).stream().filter(button -> "+".equals(button.getText())).findFirst().orElseThrow().doClick();
            double zoom = view.zoom(); assertFalse(view.fit());
            window.editorTabs().preserveViewState(() -> window.editorTabs().applySettings(EditorSettings.defaults(), EditorTheme.graphite()));
            assertEquals(zoom, view.zoom()); assertFalse(view.fit()); return null;
        });
        var preview = edt(() -> (dev.rilvune.image.ui.ImageTabView)window.editorTabs().activeTab().orElseThrow());
        Point pan = edt(() -> {
            var viewport = components(preview, JScrollPane.class).getFirst().getViewport();
            viewport.setViewPosition(new Point(30, 40)); return viewport.getViewPosition();
        });
        edt(() -> preview.controller().reload()).get(8, TimeUnit.SECONDS);
        assertEquals(pan, edt(() -> components(preview, JScrollPane.class).getFirst().getViewport().getViewPosition()));
        Path alias = Files.createSymbolicLink(temporary.resolve("alias.PNG"), image);
        edt(() -> window.openFile(alias)).get(8, TimeUnit.SECONDS);
        assertEquals(imageId, edt(() -> window.editorTabs().activeId())); assertEquals(2, edt(() -> window.editorTabs().tabs().size()));
        snapshot(window.snapshotComponent(), Path.of("build/ui-snapshots/image-preview.png"));
        var order = edt(() -> window.editorTabs().tabOrder());
        edt(window::finishSnapshot).get(8, TimeUnit.SECONDS);
        MainWindow reopened = window(temporary, temporary, profile);
        assertEquals(2, edt(() -> reopened.editorTabs().tabs().size()));
        assertEquals(imageId, edt(() -> reopened.editorTabs().activeId()));
        assertEquals(imageId, order.getLast());
        assertEquals(List.of(edt(() -> reopened.editorTabs().views().getFirst().session().id().toString()), imageId), edt(() -> reopened.editorTabs().tabOrder()));
        assertEquals(1, documentServices.get(reopened).sessions().size());
        assertEquals("unchanged text\n", Files.readString(text));
        assertTrue(edt(() -> ((dev.rilvune.image.ui.ImageTabView) reopened.editorTabs().activeTab().orElseThrow()).fit()));
    }
    @Test void badAndMissingImagePreviewsRemainReadOnlyRetryableTabsAcrossRestart() throws Exception {
        Path bad = Files.writeString(temporary.resolve("broken.PNG"), "not an image");
        Path profile = temporary.resolve("profile"); MainWindow window = window(temporary, temporary, profile);
        edt(() -> window.openFile(bad)).get(8, TimeUnit.SECONDS);
        String id = edt(() -> window.editorTabs().activeId());
        assertEquals(dev.rilvune.image.domain.ImageTabState.Phase.FAILED, edt(() -> ((dev.rilvune.image.ui.ImageTabView)window.editorTabs().activeTab().orElseThrow()).controller().model().state().phase()));
        assertTrue(documentServices.get(window).sessions().isEmpty());
        edt(window::finishSnapshot).get(8, TimeUnit.SECONDS); Files.delete(bad);
        MainWindow reopened = window(temporary, temporary, profile);
        assertEquals(id, edt(() -> reopened.editorTabs().activeId()));
        var view = edt(() -> (dev.rilvune.image.ui.ImageTabView)reopened.editorTabs().activeTab().orElseThrow());
        await(() -> edtUnchecked(() -> view.controller().model().state().phase() != dev.rilvune.image.domain.ImageTabState.Phase.LOADING));
        assertEquals(dev.rilvune.image.domain.ImageTabState.Phase.MISSING, edt(() -> view.controller().model().state().phase()));
        javax.imageio.ImageIO.write(new BufferedImage(5, 7, BufferedImage.TYPE_INT_RGB), "png", bad.toFile());
        edt(() -> view.controller().reload()).get(8, TimeUnit.SECONDS);
        assertEquals(dev.rilvune.image.domain.ImageTabState.Phase.READY, edt(() -> view.controller().model().state().phase()));
        assertTrue(documentServices.get(reopened).sessions().isEmpty());
    }

    private MainWindow window(Path browse, Path workspace, Path profile) throws Exception {
        return window(browse, workspace, profile, Executors.newVirtualThreadPerTaskExecutor());
    }
    private MainWindow window(Path browse, Path workspace, Path profile, ExecutorService io) throws Exception {
        Files.createDirectories(profile); ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        ExecutorService syntaxWorkers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        NioWorkspaceFiles files = new NioWorkspaceFiles(2 * 1024 * 1024);
        SettingsService settings = new SettingsService(new PropertiesSettingsStore(profile), io, SwingUtilities::invokeLater);
        DocumentService documents = new DocumentService(new NioDocumentStore(2 * 1024 * 1024, profile.resolve("locks"), browse), new FileDraftRepository(profile.resolve("drafts")), io, scheduler, SwingUtilities::invokeLater, Duration.ofMillis(200));
        MainWindow window = edt(() -> { EditorTheme theme = EditorTheme.from(settings.current()); theme.install();
            var syntax = new SyntaxProjectionRegistry(new SyntaxCatalog(List.of(new RstaSyntaxProvider().contribution())), SyntaxLimits.interactive(), syntaxWorkers);
            MainWindow created = new MainWindow(theme, workspace == null ? null : files.openWorkspace(workspace), files, documents, new DocumentBindings(documents, io), new dev.rilvune.diff.infrastructure.MyersDiffEngine(dev.rilvune.diff.infrastructure.DiffLimits.interactive()), settings, new SessionRepository(profile), new WorkspaceOperations(browse, profile), browse, new FontCatalog(), io, () -> { syntax.close(); syntaxWorkers.shutdownNow(); scheduler.shutdownNow(); io.shutdown(); });
            created.installSyntax(syntax); var gitStore = new PropertiesGitViewStateStore(profile);
            var repository = new CliGitRepository(GitConfiguration.defaults());
            created.installHistory(new FileHistoryWindow(created, new GitWorkspaceService(repository, io, SwingUtilities::invokeLater),
                    new dev.rilvune.diff.infrastructure.MyersDiffEngine(dev.rilvune.diff.infrastructure.DiffLimits.interactive()), io,
                    new TextEditorSettings(4, true, false, true, false, true), theme, created::openFile));
            var projectGit = new ProjectGitStatusService(repository, new GitObservation(io), io, scheduler, SwingUtilities::invokeLater, ProjectGitStatusService.Configuration.defaults(), failure -> { throw new AssertionError(failure); });
            var gitPanel = new GitWorkspacePanel(new GitWorkspaceService(repository, io, SwingUtilities::invokeLater), projectGit, new dev.rilvune.diff.infrastructure.MyersDiffEngine(dev.rilvune.diff.infrastructure.DiffLimits.interactive()), io, new TextEditorSettings(4, true, false, true, false, true), theme, created::openFile, created::showFileHistory, created::gitEditorState);
            created.installGit(new GitWindow(created, gitPanel, created::setGitDock, gitStore, gitStore.load(), io), projectGit);
            created.installEditorGit(new dev.rilvune.git.application.EditorGitService(repository, projectGit, io, SwingUtilities::invokeLater,
                    dev.rilvune.git.application.EditorGitService.Configuration.defaults(), error -> { throw new AssertionError(error); }), java.time.Clock.systemUTC(), java.time.ZoneId.of("UTC"), java.util.Locale.ENGLISH);
            created.setVisible(true); created.sizeLayout(); return created; });
        documentServices.put(window, documents); windows.add(window); edt(window::loadProject).get(10, TimeUnit.SECONDS); return window;
    }
    private static void command(MainWindow window, String id) { window.getRootPane().getActionMap().get(id).actionPerformed(new ActionEvent(window, 0, id)); }
    private static JMenu menu(MainWindow window, String title) {
        return java.util.stream.IntStream.range(0, window.getJMenuBar().getMenuCount()).mapToObj(window.getJMenuBar()::getMenu)
                .filter(menu -> title.equals(menu.getText())).findFirst().orElseThrow();
    }
    private void answerInput(String title, String value) throws Exception { JDialog dialog = awaitComponent(JDialog.class, candidate -> title.equals(candidate.getTitle())); edt(() -> { JOptionPane pane = components(dialog, JOptionPane.class).getFirst(); pane.setInputValue(value); pane.setValue(JOptionPane.OK_OPTION); return null; }); }
    private void answerConfirm(String title) throws Exception { JDialog dialog = awaitComponent(JDialog.class, candidate -> title.equals(candidate.getTitle())); edt(() -> { components(dialog, JOptionPane.class).getFirst().setValue(JOptionPane.OK_OPTION); return null; }); }
    private static <T extends Component> T awaitComponent(Class<T> type, java.util.function.Predicate<T> condition) throws Exception {
        final Component[] result = new Component[1]; await(() -> edtUnchecked(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                if (type.isInstance(window) && condition.test(type.cast(window))) { result[0] = window; return true; }
                for (T component : components(window, type)) if ((component.isShowing() || component.getName() != null) && condition.test(component)) { result[0] = component; return true; }
            } return false;
        })); return type.cast(result[0]);
    }
    private static void await(BooleanSupplier condition) throws Exception { long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Timed out waiting for UI/disk state"); Thread.sleep(20); } }
    private static <T extends Component> List<T> components(Container root, Class<T> type) { List<T> found = new ArrayList<>(); for (Component component : root.getComponents()) { if (type.isInstance(component)) found.add(type.cast(component)); if (component instanceof Container container) found.addAll(components(container, type)); } return found; }
    private static <T> T edt(Callable<T> action) throws Exception { if (SwingUtilities.isEventDispatchThread()) return action.call(); FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(10, TimeUnit.SECONDS); }
    private static <T> T edtUnchecked(Callable<T> action) { try { return edt(action); } catch (Exception failure) { throw new RuntimeException(failure); } }
    private static void snapshot(JComponent component, Path output) throws Exception { BufferedImage image = edt(() -> { BufferedImage value = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D graphics = value.createGraphics(); component.printAll(graphics); graphics.dispose(); return value; }); Files.createDirectories(output.getParent()); javax.imageio.ImageIO.write(image, "png", output.toFile()); }
}
