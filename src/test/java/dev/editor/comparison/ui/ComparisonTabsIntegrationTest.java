package dev.editor.comparison.ui;

import dev.editor.app.ui.MainWindow;
import dev.editor.diff.domain.DiffResult;
import dev.editor.diff.domain.Side;
import dev.editor.diff.infrastructure.DiffLimits;
import dev.editor.diff.infrastructure.MyersDiffEngine;
import dev.editor.document.application.DocumentService;
import dev.editor.document.domain.SyncState;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import dev.editor.document.ui.DocumentBindings;
import dev.editor.document.ui.EditorView;
import dev.editor.platform.text.ui.TextEditor;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.FontCatalog;
import dev.editor.session.infrastructure.SessionRepository;
import dev.editor.settings.application.SettingsService;
import dev.editor.settings.infrastructure.PropertiesSettingsStore;
import dev.editor.workspace.infrastructure.NioWorkspaceFiles;
import dev.editor.workspace.infrastructure.WorkspaceOperations;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import javax.swing.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(45)
class ComparisonTabsIntegrationTest {
    @TempDir Path directory;
    private final List<Fixture> fixtures = new ArrayList<>();
    private record Fixture(MainWindow window, DocumentService documents, SettingsService settings) { }

    @AfterEach void close() throws Exception {
        edt(() -> { for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose(); return null; });
        for (Fixture fixture : fixtures) if (edt(fixture.window()::isDisplayable)) edt(fixture.window()::finishSnapshot).get(10, TimeUnit.SECONDS);
    }

    @Test void directionalTransfersShareUndoAndAutosaveAndPreserveDestinationFormat() throws Exception {
        Path a = file("левый файл.txt", "same\nleft\nend\n");
        Path b = file("right file.txt", "\uFEFFsame\r\nright\r\nend\r\n");
        Fixture fixture = fixture(directory.resolve("profile")); MainWindow window = fixture.window();
        edt(() -> window.openFile(a)).get(8, TimeUnit.SECONDS); EditorView ordinaryA = edt(() -> window.editorTabs().active().orElseThrow());
        edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS); EditorView ordinaryB = edt(() -> window.editorTabs().active().orElseThrow());
        ComparisonTabView tab = comparison(window, a, b); DiffResult before = result(tab);
        edt(() -> {
            assertSame(ordinaryA.textComponent().getDocument(), tab.leftView().textComponent().getDocument());
            assertSame(ordinaryB.textComponent().getDocument(), tab.rightView().textComponent().getDocument());
            assertTrue(button(tab.component(), "diff.useLeft").isEnabled()); button(tab.component(), "diff.useLeft").doClick();
            assertEquals("same\nleft\nend\n", ordinaryB.textComponent().getText());
            return null;
        });
        await(() -> read(b).equals("\uFEFFsame\r\nleft\r\nend\r\n"));
        edt(() -> { ordinaryB.textEditor().undo(); return null; });
        await(() -> read(b).equals("\uFEFFsame\r\nright\r\nend\r\n"));
        result(tab);
        edt(() -> { clickGutter(tab, "Use right change in left file"); return null; });
        await(() -> read(a).equals("same\nright\nend\n"));
        edt(() -> { ordinaryA.textEditor().undo(); return null; });
        await(() -> read(a).equals("same\nleft\nend\n"));
        assertEquals(2, fixture.documents().sessions().size());
        assertEquals(3, edt(() -> window.editorTabs().tabs().size()));
    }

    @Test void closingOrdinaryTabsRetainsComparisonDocumentsAndLastClosePersistsThem() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n");
        Fixture fixture = fixture(directory.resolve("profile")); MainWindow window = fixture.window();
        edt(() -> window.openFile(a)).get(8, TimeUnit.SECONDS); EditorView ordinary = edt(() -> window.editorTabs().active().orElseThrow());
        ComparisonTabView tab = comparison(window, a, b);
        assertTrue(edt(() -> window.editorTabs().requestClose(ordinary)).get(8, TimeUnit.SECONDS));
        assertFalse(tab.leftView().session().isClosed());
        edt(() -> { assertTrue(tab.leftView().textComponent().isEditable()); tab.leftView().textComponent().append("kept\n"); return null; });
        assertTrue(edt(() -> window.editorTabs().requestClose(tab.id())).get(8, TimeUnit.SECONDS));
        assertEquals("a\nkept\n", Files.readString(a));
        assertTrue(fixture.documents().sessions().isEmpty());
        assertEquals(0, edt(() -> window.editorTabs().tabs().size()));
    }
    @Test void mixedRefinedBlockStillTransfersAsOneDestinationUndoStep() throws Exception {
        String before = "port = 8080\nworkers = 2\nobsolete = true\nmode = keep\n";
        String after = "port = 9090\ntimeout = 30\nworkers = 4\nmode = keep\n";
        Path a = file("a.txt", before), b = file("b.txt", after);
        MainWindow window = fixture(directory.resolve("profile")).window(); ComparisonTabView tab = comparison(window, a, b);
        DiffResult diff = result(tab); assertEquals(1, diff.hunks().size()); assertEquals(4, diff.hunks().getFirst().changes().size());
        edt(() -> { assertTrue(tab.transfer(diff.hunks().getFirst(), Side.LEFT)); return null; });
        await(() -> read(b).equals(before));
        edt(() -> { tab.rightView().textEditor().undo(); return null; });
        await(() -> read(b).equals(after)); result(tab);
        assertEquals(before, tab.leftView().session().text());
    }

    @Test void staleTransfersAreRejectedAndExternalChangesRefreshBothViewsAndDiff() throws Exception {
        Path a = file("a.txt", "base\na\n"), b = file("b.txt", "base\nb\n");
        Fixture fixture = fixture(directory.resolve("profile")); MainWindow window = fixture.window();
        ComparisonTabView tab = comparison(window, a, b); DiffResult old = result(tab);
        edt(() -> {
            tab.rightView().textComponent().append("new edit\n");
            assertFalse(tab.transfer(old.hunks().getFirst(), Side.LEFT));
            assertEquals("base\nb\nnew edit\n", tab.rightView().session().text());
            return null;
        });
        await(() -> read(b).equals("base\nb\nnew edit\n")); result(tab);
        Files.writeString(a, "external\na\n"); fixture.documents().refreshAll();
        await(() -> ui(() -> tab.leftView().textComponent().getText().equals("external\na\n")));
        DiffResult external = result(tab); assertEquals("external\na\n", external.left().text());
        tab.rightView().session().pause().get(8, TimeUnit.SECONDS);
        edt(() -> { assertFalse(tab.transfer(external.hunks().getFirst(), Side.LEFT)); tab.rightView().session().resume(); return null; });
        Files.delete(b); fixture.documents().refreshAll();
        await(() -> tab.rightView().session().state() == SyncState.DELETED);
        DiffResult deleted = result(tab);
        edt(() -> { assertFalse(tab.transfer(deleted.hunks().getFirst(), Side.LEFT)); return null; });
        assertFalse(Files.exists(b), "A transfer must never silently recreate a deleted destination");
    }

    @Test void unifiedProjectionRemainsReadOnlySearchableAndStableAcrossLiveRefresh() throws Exception {
        Path a = file("a.txt", "same\nleft\n"), b = file("b.txt", "same\nright\n");
        MainWindow window = fixture(directory.resolve("profile")).window(); ComparisonTabView tab = comparison(window, a, b);
        edt(() -> { combo(tab, "diff.mode").setSelectedIndex(1); return null; });
        TextEditor unified = edt(() -> tab.textTarget().orElseThrow());
        edt(() -> {
            assertFalse(unified.editable()); assertTrue(unified.textComponent().getText().contains("-left\n+right\n"));
            unified.textComponent().setEditable(true); unified.textComponent().setText("forbidden");
            assertFalse(unified.textComponent().isEditable()); assertFalse(unified.textComponent().getText().equals("forbidden"));
            unified.caret(3); unified.find(false); tab.diffView().navigate(1);
            assertFalse(button(tab.component(), "diff.useLeft").isEnabled());
            assertFalse(button(tab.component(), "diff.useLeft").isShowing());
            tab.leftView().textComponent().append("later\n"); return null;
        });
        result(tab);
        edt(() -> {
            assertSame(unified, tab.textTarget().orElseThrow());
            assertEquals(3, unified.textComponent().getCaretPosition());
            assertTrue(unified.textComponent().getText().contains("-later\n"));
            assertTrue(components(unified, JTextField.class).stream().anyMatch(Component::isShowing));
            return null;
        });
        snapshot(tab.component(), Path.of("build/ui-snapshots/comparison-unified.png"));
    }

    @Test void treeComparesUnopenedSourceWithTheActiveLiveFileWithoutAChooser() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n"), c = file("c.txt", "c\n");
        MainWindow window = fixture(directory.resolve("profile")).window();
        edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS); edt(() -> window.openFile(c)).get(8, TimeUnit.SECONDS);
        EditorView current = edt(() -> window.editorTabs().active().orElseThrow());
        edt(() -> { current.textComponent().append("live edit\n"); return null; });
        JMenuItem compare = treeComparison(window, a);
        edt(() -> {
            assertTrue(compare.isEnabled()); assertTrue(compare.getToolTipText().contains("c.txt"));
            assertEquals(current.session().id().toString(), window.editorTabs().activeId()); compare.doClick(); return null;
        });
        await(() -> ui(() -> window.fileComparisons().liveTabs().size() == 1));
        ComparisonTabView first = edt(() -> window.fileComparisons().liveTabs().getFirst()); result(first);
        edt(() -> {
            assertEquals(a, first.leftView().session().path()); assertSame(current.session(), first.rightView().session());
            assertSame(current.textComponent().getDocument(), first.rightView().textComponent().getDocument());
            assertEquals("c\nlive edit\n", first.rightView().textComponent().getText());
            assertEquals(2, window.editorTabs().views().size(), "The clicked file needs no ordinary editing tab");
            assertTrue(java.util.Arrays.stream(Window.getWindows()).noneMatch(item -> item instanceof JDialog && item.isShowing()));
            window.editorTabs().select(current.session().id()); return null;
        });
        JMenuItem repeated = treeComparison(window, a); edt(() -> { repeated.doClick(); return null; });
        await(() -> ui(() -> window.editorTabs().activeId().equals(first.id())));
        assertEquals(1, edt(() -> window.fileComparisons().liveTabs().size()));
    }

    @Test void inactiveTabMenusResolveTheCurrentFileEachTimeWithoutActivatingTheirSource() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n"), c = file("c.txt", "c\n");
        MainWindow window = fixture(directory.resolve("profile")).window();
        edt(() -> window.openFile(a)).get(8, TimeUnit.SECONDS); EditorView source = edt(() -> window.editorTabs().active().orElseThrow());
        edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS); EditorView firstTarget = edt(() -> window.editorTabs().active().orElseThrow());
        popupTab(window, source, false);
        JMenuItem firstAction = showing(JMenuItem.class, item -> "Compare File With Editor".equals(item.getText()));
        edt(() -> {
            assertEquals(firstTarget.session().id().toString(), window.editorTabs().activeId());
            assertTrue(firstAction.isEnabled()); assertTrue(firstAction.getToolTipText().contains("b.txt")); clickMenu(firstAction); return null;
        });
        await(() -> ui(() -> window.fileComparisons().liveTabs().size() == 1));
        assertEquals(b, edt(() -> window.fileComparisons().liveTabs().getFirst().rightView().session().path()));
        edt(() -> window.openFile(c)).get(8, TimeUnit.SECONDS); EditorView nextTarget = edt(() -> window.editorTabs().active().orElseThrow());
        popupTab(window, source, true);
        JMenuItem nextAction = showing(JMenuItem.class, item -> "Compare File With Editor".equals(item.getText()));
        edt(() -> {
            assertEquals(nextTarget.session().id().toString(), window.editorTabs().activeId());
            assertTrue(nextAction.isEnabled()); assertTrue(nextAction.getToolTipText().contains("c.txt")); clickMenu(nextAction); return null;
        });
        await(() -> ui(() -> window.fileComparisons().liveTabs().size() == 2));
        assertEquals(c, edt(() -> window.fileComparisons().liveTabs().getLast().rightView().session().path()));
    }

    @Test void comparisonIsUnavailableWithoutAnActiveFileForTheSameFileAndAfterTargetClose() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n");
        MainWindow window = fixture(directory.resolve("profile")).window();
        JMenuItem noTarget = treeComparison(window, a); assertFalse(edt(noTarget::isEnabled));
        edt(() -> window.openFile(a)).get(8, TimeUnit.SECONDS);
        JMenuItem same = treeComparison(window, a); assertFalse(edt(same::isEnabled));
        JMenuItem directoryAction = treeComparison(window, directory); assertFalse(edt(directoryAction::isEnabled));
        EditorView ordinary = edt(() -> window.editorTabs().active().orElseThrow());
        edt(() -> {
            assertTrue(components(ordinary.textComponent().getComponentPopupMenu(), JMenuItem.class).stream().noneMatch(item -> item.getText().startsWith("Compare File With Editor")));
            assertNull(window.getRootPane().getActionMap().get("file.compare")); return null;
        });
        edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS); EditorView target = edt(() -> window.editorTabs().active().orElseThrow());
        JMenuItem captured = treeComparison(window, a); assertTrue(edt(captured::isEnabled));
        assertTrue(edt(() -> window.editorTabs().requestClose(target)).get(8, TimeUnit.SECONDS));
        edt(() -> { captured.doClick(); assertTrue(window.fileComparisons().liveTabs().isEmpty()); return null; });
    }

    @Test void focusedComparisonSourceIsCurrentButUnifiedProjectionIsNotAFile() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n"), c = file("c.txt", "c\n");
        MainWindow window = fixture(directory.resolve("profile")).window();
        edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS); ComparisonTabView first = comparison(window, a, b);
        edt(() -> { first.diffView().focus(Side.RIGHT); return null; });
        await(() -> ui(() -> first.rightView().textComponent().isFocusOwner()));
        JMenuItem action = treeComparison(window, c); edt(() -> { assertTrue(action.isEnabled()); action.doClick(); return null; });
        await(() -> ui(() -> window.fileComparisons().liveTabs().size() == 2));
        assertEquals(b, edt(() -> window.fileComparisons().liveTabs().getLast().rightView().session().path()));
        edt(() -> { window.editorTabs().select(first.id()); combo(first, "diff.mode").setSelectedIndex(1); return null; });
        JMenuItem projection = treeComparison(window, c); assertFalse(edt(projection::isEnabled));
    }

    @Test void searchResultComparesDirectlyWithTheActiveFileAndClosesSearch() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n");
        MainWindow window = fixture(directory.resolve("profile")).window(); edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS);
        SwingUtilities.invokeLater(() -> command(window, "navigate.file"));
        JTextField query = showing(JTextField.class, field -> "projectSearchQuery".equals(field.getName()));
        edt(() -> { query.setText("a.txt"); return null; });
        JList<?> results = showing(JList.class, list -> "projectSearchResults".equals(list.getName()));
        await(() -> ui(() -> results.getModel().getSize() == 1));
        JDialog search = (JDialog) SwingUtilities.getWindowAncestor(query);
        edt(() -> { Rectangle row = results.getCellBounds(0, 0); popup(results, results.getWidth() - 8, row.y + row.height / 2); return null; });
        JMenuItem action = showing(JMenuItem.class, item -> "Compare File With Editor".equals(item.getText()));
        edt(() -> { assertTrue(action.isEnabled()); assertTrue(action.getToolTipText().contains("b.txt")); clickMenu(action); return null; });
        await(() -> ui(() -> window.fileComparisons().liveTabs().size() == 1));
        edt(() -> {
            assertFalse(search.isDisplayable()); ComparisonTabView tab = window.fileComparisons().liveTabs().getFirst();
            assertEquals(a, tab.leftView().session().path()); assertEquals(b, tab.rightView().session().path());
            assertEquals(1, window.editorTabs().views().size()); return null;
        });
    }

    @Test void fontApplySwapAndRestartPreserveLiveDocumentsAndMixedTabOrder() throws Exception {
        Path a = file("a.txt", "same\nleft\n" + "unchanged\n".repeat(100)), b = file("b.txt", "same\nright\n" + "unchanged\n".repeat(100));
        Path profile = directory.resolve("profile"); Fixture fixture = fixture(profile); MainWindow window = fixture.window();
        edt(() -> window.openFile(a)).get(8, TimeUnit.SECONDS); ComparisonTabView tab = comparison(window, a, b);
        edt(() -> window.openFile(b)).get(8, TimeUnit.SECONDS);
        edt(() -> {
            window.editorTabs().select(tab.id()); tab.diffView().withoutScrollSync(() -> {
                tab.leftView().textEditor().restoreView(new TextEditor.ViewState(7, 5, new Point(0, 140)));
                tab.rightView().textEditor().restoreView(new TextEditor.ViewState(10, 8, new Point(0, 220)));
            });
            return null;
        }); edt(() -> null);
        var beforeLeft = edt(() -> tab.leftView().textEditor().captureView()); var beforeRight = edt(() -> tab.rightView().textEditor().captureView());
        var fonts = fixture.settings().current().withFonts("Hack", 16, "Hack", 19); fixture.settings().apply(fonts).get(8, TimeUnit.SECONDS); edt(() -> null);
        edt(() -> {
            assertEquals(19, tab.leftView().textComponent().getFont().getSize()); assertEquals(19, tab.rightView().textComponent().getFont().getSize());
            assertEquals(beforeLeft, tab.leftView().textEditor().captureView()); assertEquals(beforeRight, tab.rightView().textEditor().captureView());
            tab.swap(); return null;
        }); result(tab);
        assertEquals(b, tab.leftView().session().path());
        edt(() -> { combo(tab, "diff.whitespace").setSelectedIndex(1); combo(tab, "diff.mode").setSelectedIndex(1); return null; }); result(tab);
        var saved = edt(tab::state); var order = edt(() -> window.editorTabs().tabOrder());
        edt(window::finishSnapshot).get(10, TimeUnit.SECONDS);
        MainWindow reopened = fixture(profile).window();
        ComparisonTabView restored = edt(() -> reopened.fileComparisons().liveTabs().getFirst()); restored.ready().get(8, TimeUnit.SECONDS);
        var actual = edt(restored::state);
        assertEquals(saved.id(), actual.id()); assertEquals(saved.left().path(), actual.left().path()); assertEquals(saved.right().path(), actual.right().path());
        assertEquals(saved.leftCaret(), actual.leftCaret()); assertEquals(saved.rightCaret(), actual.rightCaret());
        assertEquals(saved.leftScroll(), actual.leftScroll()); assertEquals(saved.rightScroll(), actual.rightScroll());
        assertTrue(actual.unified()); assertTrue(actual.ignoreWhitespace());
        assertEquals(tab.id(), edt(() -> reopened.editorTabs().activeId()));
        assertEquals(3, edt(() -> reopened.editorTabs().tabOrder().size()));
        assertEquals(order.indexOf(tab.id()), edt(() -> reopened.editorTabs().tabOrder().indexOf(tab.id())));
        assertEquals(2, edt(() -> reopened.fileComparisons().liveTabs().getFirst().documents().size()));
        snapshot(restored.component(), Path.of("build/ui-snapshots/comparison-restored.png"));
    }

    @Test void missingSourcesRestoreAsRetryablePlaceholdersWithoutCreatingFiles() throws Exception {
        Path a = file("a.txt", "a\n"), b = file("b.txt", "b\n"), profile = directory.resolve("profile");
        MainWindow window = fixture(profile).window(); ComparisonTabView tab = comparison(window, a, b);
        edt(window::finishSnapshot).get(10, TimeUnit.SECONDS); Files.delete(b);
        MainWindow reopened = fixture(profile).window();
        assertTrue(edt(() -> reopened.fileComparisons().liveTabs().isEmpty())); assertFalse(Files.exists(b));
        var unavailable = edt(() -> reopened.editorTabs().tabs().stream().filter(UnavailableComparisonTabView.class::isInstance).findFirst().orElseThrow());
        assertEquals(tab.id(), unavailable.id()); Files.writeString(b, "restored\n");
        edt(() -> { components(unavailable.component(), JButton.class).stream().filter(button -> "Retry".equals(button.getText())).findFirst().orElseThrow().doClick(); return null; });
        await(() -> ui(() -> reopened.fileComparisons().liveTabs().size() == 1));
        ComparisonTabView restored = edt(() -> reopened.fileComparisons().liveTabs().getFirst()); result(restored);
        assertEquals(tab.id(), restored.id()); assertEquals("restored\n", restored.rightView().session().text());
    }

    @Test void projectSwitchCannotPublishAnObsoleteFailedComparisonRestore() throws Exception {
        Path a = file("a.txt", "a\n"), missing = directory.resolve("missing.txt"), next = Files.createDirectory(directory.resolve("next"));
        ExecutorService io = Executors.newFixedThreadPool(2); Fixture fixture = fixture(directory.resolve("profile"), io);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        io.execute(() -> { entered.countDown(); try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var state = new dev.editor.session.domain.SessionState.ComparisonTabDescriptor("comparison:obsolete",
                new dev.editor.session.domain.SessionState.Source("", a), new dev.editor.session.domain.SessionState.Source("", missing), 0, 0, 0, 0, false, false);
        try {
            CompletableFuture<Void> obsolete = edt(() -> fixture.window().fileComparisons().restore(state));
            CompletableFuture<Void> switched = edt(() -> fixture.window().openProject(next)); release.countDown();
            obsolete.get(8, TimeUnit.SECONDS); switched.get(8, TimeUnit.SECONDS);
            assertEquals(next, edt(() -> fixture.window().currentWorkspace().root()));
            assertTrue(edt(() -> fixture.window().editorTabs().tabs().isEmpty()));
        } finally { release.countDown(); }
    }

    private Fixture fixture(Path profile) throws Exception { return fixture(profile, Executors.newVirtualThreadPerTaskExecutor()); }
    private Fixture fixture(Path profile, ExecutorService io) throws Exception {
        Files.createDirectories(profile); ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        var files = new NioWorkspaceFiles(2 * 1024 * 1024);
        var settings = new SettingsService(new PropertiesSettingsStore(profile), io, SwingUtilities::invokeLater);
        var documents = new DocumentService(new NioDocumentStore(2 * 1024 * 1024, profile.resolve("locks"), directory),
                new FileDraftRepository(profile.resolve("drafts")), io, scheduler, SwingUtilities::invokeLater, Duration.ofMillis(150));
        MainWindow window = edt(() -> {
            EditorTheme theme = EditorTheme.from(settings.current()); theme.install();
            var result = new MainWindow(theme, files.openWorkspace(directory), files, documents, new DocumentBindings(documents),
                    new MyersDiffEngine(DiffLimits.interactive()), settings, new SessionRepository(profile), new WorkspaceOperations(directory, profile),
                    directory, new FontCatalog(), io, () -> { scheduler.shutdownNow(); io.shutdown(); });
            result.setVisible(true); result.sizeLayout(); return result;
        });
        Fixture fixture = new Fixture(window, documents, settings); fixtures.add(fixture);
        edt(window::loadProject).get(10, TimeUnit.SECONDS); return fixture;
    }
    private Path file(String name, String text) throws Exception { return Files.writeString(directory.resolve(name), text); }
    private ComparisonTabView comparison(MainWindow window, Path left, Path right) throws Exception {
        ComparisonTabView tab = edt(() -> window.openComparison(left, right)).get(8, TimeUnit.SECONDS);
        tab.ready().get(8, TimeUnit.SECONDS); result(tab); return tab;
    }
    private DiffResult result(ComparisonTabView tab) throws Exception {
        await(() -> ui(() -> tab.diffView().result().isPresent())); return edt(() -> tab.diffView().result().orElseThrow());
    }
    private static JComboBox<?> combo(ComparisonTabView tab, String name) { return components(tab.component(), JComboBox.class).stream().filter(item -> name.equals(item.getName())).findFirst().orElseThrow(); }
    private static JButton button(Container root, String name) { return components(root, JButton.class).stream().filter(item -> name.equals(item.getName())).findFirst().orElseThrow(); }
    private static void clickGutter(ComparisonTabView tab, String tooltip) {
        JComponent gutter = components(tab.component(), JComponent.class).stream().filter(item -> "diff.gutter".equals(item.getName())).findFirst().orElseThrow();
        var image = new BufferedImage(gutter.getWidth(), gutter.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); gutter.paint(graphics); graphics.dispose();
        for (int y = 0; y < gutter.getHeight(); y++) for (int x : new int[]{15, gutter.getWidth() - 15}) {
            var event = new java.awt.event.MouseEvent(gutter, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1);
            if (tooltip.equals(gutter.getToolTipText(event))) { gutter.dispatchEvent(event); return; }
        }
        fail("No visible gutter arrow: " + tooltip);
    }
    private JMenuItem treeComparison(MainWindow window, Path path) throws Exception {
        edt(() -> window.projectTree().select(path)).get(8, TimeUnit.SECONDS);
        return edt(() -> menuItem(window.projectTree().contextMenuForSelection(), "Compare File With Editor"));
    }
    private void popupTab(MainWindow window, EditorView source, boolean paneBackground) throws Exception {
        edt(() -> {
            JTabbedPane pane = components(window.editorTabs(), JTabbedPane.class).getFirst();
            int index = pane.indexOfComponent(source);
            if (paneBackground) { Rectangle bounds = pane.getBoundsAt(index); popup(pane, bounds.x + 2, bounds.y + 2); }
            else { Component label = ((Container) pane.getTabComponentAt(index)).getComponent(0); popup(label, 4, 4); }
            return null;
        });
    }
    private static void popup(Component component, int x, int y) {
        component.dispatchEvent(new java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true, java.awt.event.MouseEvent.BUTTON3));
        component.dispatchEvent(new java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON3));
    }
    private static void clickMenu(JMenuItem item) { ((JPopupMenu) item.getParent()).setVisible(false); item.doClick(); }
    private static JMenuItem menuItem(JPopupMenu menu, String text) { return components(menu, JMenuItem.class).stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow(); }
    private static void command(MainWindow window, String id) { window.getRootPane().getActionMap().get(id).actionPerformed(new ActionEvent(window, 0, id)); }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { return ""; } }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Timed out waiting for UI/disk state"); Thread.sleep(20); }
    }
    private static <T extends Component> T showing(Class<T> type, Predicate<T> condition) throws Exception {
        Component[] found = new Component[1]; await(() -> ui(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) for (T item : components(window, type)) {
                if (item.isShowing() && condition.test(item)) { found[0] = item; return true; }
            }
            return false;
        })); return type.cast(found[0]);
    }
    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>(); for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container container) found.addAll(components(container, type));
        } return found;
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return task.call(); FutureTask<T> result = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(result); return result.get(10, TimeUnit.SECONDS);
    }
    private static boolean ui(Callable<Boolean> task) { try { return edt(task); } catch (Exception error) { throw new RuntimeException(error); } }
    private static void snapshot(JComponent component, Path path) throws Exception {
        BufferedImage image = edt(() -> { var result = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = result.createGraphics(); component.printAll(graphics); graphics.dispose(); return result; });
        Files.createDirectories(path.getParent()); javax.imageio.ImageIO.write(image, "png", path.toFile());
    }
}
