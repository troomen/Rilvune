package dev.rilvune.workspace.ui;

import dev.rilvune.workspace.domain.TextFile;
import dev.rilvune.workspace.domain.Workspace;
import dev.rilvune.workspace.domain.WorkspaceEntry;
import dev.rilvune.workspace.domain.WorkspaceFiles;
import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTreeViewTest {
    @Test void projectSelectionExportsFilesUsingTheStandardDesktopTransferFlavor() throws Exception {
        Workspace workspace = new Workspace(Path.of("project")); Path a = workspace.root().resolve("a.txt"), b = workspace.root().resolve("b.txt");
        Harness harness = createHarness(Map.of(workspace.root(), List.of(file(a), file(b))));
        onEdt(() -> harness.view().showWorkspace(workspace)); harness.executor().runFirst(); flushEdt();
        onEdt(() -> {
            harness.tree().setSelectionRows(new int[] {0, 1, 2});
            var clipboard = new java.awt.datatransfer.Clipboard("project-files");
            var handler = harness.tree().getTransferHandler(); assertEquals(javax.swing.TransferHandler.COPY, handler.getSourceActions(harness.tree()));
            handler.exportToClipboard(harness.tree(), clipboard, javax.swing.TransferHandler.COPY);
            assertEquals(List.of(a.toFile(), b.toFile()), clipboard.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor));
            return null;
        });
    }
    @Test void statusDecorationsPreserveNodesSelectionExpansionAndViewportWithoutLoadingFiles() throws Exception {
        Workspace workspace = new Workspace(Path.of("project")); Path selected = workspace.root().resolve("selected.txt");
        Harness harness = createHarness(Map.of(workspace.root(), List.of(file(selected))));
        onEdt(() -> harness.view().showWorkspace(workspace)); harness.executor().runFirst(); flushEdt();
        onEdt(() -> harness.view().select(selected));
        onEdt(() -> {
            Object root = harness.tree().getModel().getRoot(); TreePath selection = harness.tree().getSelectionPath();
            Rectangle visible = harness.tree().getVisibleRect(); int rows = harness.tree().getRowCount();
            harness.view().setDecorations(new dev.rilvune.platform.decoration.PathDecorations(workspace.root(),
                    Map.of(selected, new dev.rilvune.platform.decoration.PathDecoration(dev.rilvune.platform.decoration.PathDecoration.Tone.MODIFIED, "Modified")), ""));
            org.junit.jupiter.api.Assertions.assertSame(root, harness.tree().getModel().getRoot());
            assertEquals(selection, harness.tree().getSelectionPath()); assertEquals(visible, harness.tree().getVisibleRect());
            assertEquals(rows, harness.tree().getRowCount()); assertTrue(harness.tree().isExpanded(0)); return null;
        });
    }

    @Test
    void branchRefreshKeepsExpandedNodesAndSelectionWhileAddingExternalFiles() throws Exception {
        Workspace workspace = new Workspace(Path.of("project"));
        Path directory = workspace.root().resolve("source");
        Path selected = directory.resolve("selected.txt");
        Map<Path, List<WorkspaceEntry>> listings = new HashMap<>();
        listings.put(workspace.root(), List.of(directory(directory)));
        listings.put(directory, List.of(file(selected)));
        Harness harness = createHarness(listings);
        onEdt(() -> harness.view().showWorkspace(workspace));
        harness.executor().runFirst();
        flushEdt();
        onEdt(() -> harness.view().select(selected));
        harness.executor().runFirst();
        flushEdt();

        listings.put(directory, List.of(file(selected), file(directory.resolve("external.txt"))));
        onEdt(() -> harness.view().refreshPreservingState());
        harness.executor().runAll();
        flushEdt();

        onEdt(() -> {
            assertEquals(Optional.of(selected), harness.view().selectedPath());
            assertEquals(Set.of(workspace.root(), directory), harness.view().expandedDirectories());
            TreePath sourcePath = harness.tree().getPathForRow(1);
            assertTrue(harness.tree().isExpanded(sourcePath));
            DefaultMutableTreeNode source = (DefaultMutableTreeNode) sourcePath.getLastPathComponent();
            assertEquals(2, source.getChildCount());
            return null;
        });
    }

    @Test
    void refreshDoesNotReopenCollapsedRootAndContextMenusReceiveCurrentSelection() throws Exception {
        Workspace workspace = new Workspace(Path.of("project"));
        Harness harness = createHarness(Map.of(workspace.root(), List.of(file(workspace.root().resolve("a.txt")))));
        onEdt(() -> harness.view().showWorkspace(workspace));
        harness.executor().runFirst();
        flushEdt();
        onEdt(() -> {
            harness.tree().collapseRow(0);
            harness.view().setContextMenuProvider(selection -> {
                JPopupMenu menu = new JPopupMenu();
                menu.add(new JMenuItem(selection.map(Path::toString).orElse("empty")));
                return menu;
            });
            assertEquals(workspace.root().toString(), ((JMenuItem) harness.view().contextMenuForSelection().getComponent(0)).getText());
            harness.tree().clearSelection();
            assertEquals("empty", ((JMenuItem) harness.view().contextMenuForSelection().getComponent(0)).getText());
            return harness.view().refreshPreservingState();
        });
        harness.executor().runFirst();
        flushEdt();
        onEdt(() -> { assertFalse(harness.tree().isExpanded(0)); return null; });
    }

    @Test
    void dotfilesRemainVisibleAndExcludedDirectoriesCanBeShownExplicitly() throws Exception {
        Workspace workspace = new Workspace(Path.of("project"));
        Harness harness = createHarness(Map.of(workspace.root(), List.of(
                directory(workspace.root().resolve(".git")), file(workspace.root().resolve(".env")))));
        onEdt(() -> harness.view().showWorkspace(workspace));
        harness.executor().runFirst();
        flushEdt();
        onEdt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) harness.tree().getModel().getRoot();
            assertEquals(1, root.getChildCount());
            assertEquals(".env", root.getChildAt(0).toString());
            harness.view().setShowExcludedDirectories(true);
            return null;
        });
        harness.executor().runFirst();
        flushEdt();
        onEdt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) harness.tree().getModel().getRoot();
            assertEquals(2, root.getChildCount());
            return null;
        });
    }

    @Test
    void staleWorkspaceResultCannotReplaceTheCurrentProject() throws Exception {
        Workspace previous = new Workspace(Path.of("previous-project"));
        Workspace current = new Workspace(Path.of("current-project"));
        Harness harness = createHarness(Map.of(
                previous.root(), List.of(file(previous.root().resolve("old.txt"))),
                current.root(), List.of(file(current.root().resolve("current.txt")))));
        CompletableFuture<Void> previousLoad = onEdt(() -> harness.view().showWorkspace(previous));
        CompletableFuture<Void> currentLoad = onEdt(() -> harness.view().showWorkspace(current));

        harness.executor().runLast();
        flushEdt();
        assertTrue(currentLoad.isDone());
        harness.executor().runFirst();
        flushEdt();

        assertTrue(previousLoad.isDone());
        onEdt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) harness.tree().getModel().getRoot();
            assertEquals("current-project", root.toString());
            assertEquals(1, root.getChildCount());
            assertEquals("current.txt", root.getChildAt(0).toString());
            return null;
        });
    }

    @Test
    void finishingDirectoryLoadPreservesTheUsersCollapse() throws Exception {
        Workspace workspace = new Workspace(Path.of("project"));
        Path source = workspace.root().resolve("source");
        Harness harness = createHarness(Map.of(
                workspace.root(), List.of(directory(source)),
                source, List.of(file(source.resolve("Example.txt")))));
        onEdt(() -> harness.view().showWorkspace(workspace));
        harness.executor().runFirst();
        flushEdt();
        TreePath path = onEdt(() -> {
            TreePath directoryPath = harness.tree().getPathForRow(1);
            assertNotNull(directoryPath);
            harness.tree().expandPath(directoryPath);
            harness.tree().collapsePath(directoryPath);
            return directoryPath;
        });

        harness.executor().runFirst();
        flushEdt();

        onEdt(() -> {
            assertFalse(harness.tree().isExpanded(path));
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            assertEquals("Example.txt", node.getChildAt(0).toString());
            return null;
        });
    }

    @Test
    void doubleClickCallbackLeavesDirectoryExpansionToSwingAndStillOpensFiles() throws Exception {
        Workspace workspace = new Workspace(Path.of("project"));
        Path source = workspace.root().resolve("source");
        Path example = source.resolve("Example.txt");
        Harness harness = createHarness(Map.of(
                workspace.root(), List.of(directory(source)),
                source, List.of(file(example))));
        onEdt(() -> harness.view().showWorkspace(workspace));
        harness.executor().runFirst();
        flushEdt();
        TreePath directoryPath = onEdt(() -> {
            TreePath path = harness.tree().getPathForRow(1);
            assertNotNull(path);
            // BasicTreeUI has already expanded the directory on the second mouse press.
            harness.tree().expandPath(path);
            return path;
        });
        harness.executor().runFirst();
        flushEdt();

        onEdt(() -> {
            harness.tree().setSize(320, 480);
            harness.tree().setSelectionPath(directoryPath);
            dispatchDoubleClick(harness.tree(), directoryPath);
            assertTrue(harness.tree().isExpanded(directoryPath));
            assertTrue(harness.openedFiles().isEmpty());

            DefaultMutableTreeNode directory = (DefaultMutableTreeNode) directoryPath.getLastPathComponent();
            TreePath filePath = directoryPath.pathByAddingChild(directory.getChildAt(0));
            harness.tree().setSelectionPath(filePath);
            dispatchDoubleClick(harness.tree(), filePath);
            assertEquals(List.of(example), harness.openedFiles());
            return null;
        });
    }

    @Test
    void doubleClickUsesWholeRowAndIgnoresEmptySpaceBelowRows() throws Exception {
        Workspace workspace = new Workspace(Path.of("project"));
        Path file = workspace.root().resolve("note.txt");
        Harness harness = createHarness(Map.of(workspace.root(), List.of(file(file))));
        onEdt(() -> harness.view().showWorkspace(workspace)); harness.executor().runFirst(); flushEdt();
        onEdt(() -> {
            harness.tree().setSize(420, 480);
            TreePath filePath = harness.tree().getPathForRow(1);
            Rectangle fileBounds = harness.tree().getPathBounds(filePath);
            int trailingX = 400;
            assertTrue(trailingX > fileBounds.x + fileBounds.width);
            harness.tree().dispatchEvent(new MouseEvent(harness.tree(), MouseEvent.MOUSE_CLICKED, 1L, 0,
                    trailingX, fileBounds.y + fileBounds.height / 2, 2, false, MouseEvent.BUTTON1));
            assertEquals(List.of(file), harness.openedFiles());
            harness.tree().dispatchEvent(new MouseEvent(harness.tree(), MouseEvent.MOUSE_CLICKED, 2L, 0,
                    trailingX, 450, 2, false, MouseEvent.BUTTON1));
            assertEquals(List.of(file), harness.openedFiles(), "Empty space must not open the nearest file");
            return null;
        });
        harness.executor().runAll(); flushEdt();
    }

    @Test
    @org.junit.jupiter.api.Tag("ui")
    void rowBackgroundSelectsTheFileAndTargetsItsContextMenu() throws Exception {
        onEdt(() -> { EditorTheme.graphite().install(); return null; });
        Workspace workspace = new Workspace(Path.of("project"));
        Path file = workspace.root().resolve("note.txt");
        Harness harness = createHarness(Map.of(workspace.root(), List.of(file(file))));
        onEdt(() -> harness.view().showWorkspace(workspace)); harness.executor().runFirst(); flushEdt();
        onEdt(() -> {
            harness.tree().setSize(420, 480);
            Rectangle bounds = harness.tree().getPathBounds(harness.tree().getPathForRow(1));
            int y = bounds.y + bounds.height / 2;
            harness.tree().dispatchEvent(new MouseEvent(harness.tree(), MouseEvent.MOUSE_PRESSED, 1L, 0,
                    400, y, 1, false, MouseEvent.BUTTON1));
            assertEquals(Optional.of(file), harness.view().selectedPath());
            harness.tree().clearSelection();
            List<Optional<Path>> menuTargets = new ArrayList<>();
            harness.view().setContextMenuProvider(selected -> { menuTargets.add(selected); return null; });
            harness.tree().dispatchEvent(new MouseEvent(harness.tree(), MouseEvent.MOUSE_RELEASED, 2L, 0,
                    400, y, 1, true, MouseEvent.BUTTON3));
            assertEquals(List.of(Optional.of(file)), menuTargets);
            harness.tree().dispatchEvent(new MouseEvent(harness.tree(), MouseEvent.MOUSE_RELEASED, 3L, 0,
                    400, 450, 1, true, MouseEvent.BUTTON3));
            assertEquals(Optional.empty(), menuTargets.getLast());
            return null;
        });
    }

    @Test
    @org.junit.jupiter.api.Tag("ui")
    void nativeDoubleClickExpandsDirectoryOnceAcrossTheWholeRow() throws Exception {
        onEdt(() -> { EditorTheme.graphite().install(); return null; });
        for (boolean trailing : new boolean[]{false, true}) {
            Workspace workspace = new Workspace(Path.of("project"));
            Path directory = workspace.root().resolve("source");
            Harness harness = createHarness(Map.of(workspace.root(), List.of(directory(directory)),
                    directory, List.of(file(directory.resolve("Example.txt")))));
            onEdt(() -> harness.view().showWorkspace(workspace)); harness.executor().runFirst(); flushEdt();
            TreePath path = onEdt(() -> {
                JTree tree = harness.tree(); tree.setSize(420, 480);
                TreePath folder = tree.getPathForRow(1);
                dispatchRowDoubleClick(tree, folder, trailing);
                assertTrue(tree.isExpanded(folder), "Double click should expand once; trailing space: " + trailing);
                return folder;
            });
            // Complete a parent refresh while this directory's first listing is still pending.
            onEdt(() -> harness.view().refreshBranches(Set.of(workspace.root())));
            harness.executor().runLast(); flushEdt();
            harness.executor().runFirst(); flushEdt();
            onEdt(() -> {
                assertTrue(harness.tree().isExpanded(path));
                assertEquals("Example.txt", ((DefaultMutableTreeNode)path.getLastPathComponent()).getChildAt(0).toString());
                return harness.view().refreshPreservingState();
            });
            harness.executor().runAll(); flushEdt();
            onEdt(() -> {
                assertTrue(harness.tree().isExpanded(path), "A background refresh must preserve expansion");
                dispatchRowDoubleClick(harness.tree(), path, trailing);
                assertFalse(harness.tree().isExpanded(path));
                dispatchRowDoubleClick(harness.tree(), path, trailing);
                assertTrue(harness.tree().isExpanded(path));
                TreePath file = path.pathByAddingChild(((DefaultMutableTreeNode)path.getLastPathComponent()).getChildAt(0));
                dispatchRowDoubleClick(harness.tree(), file, trailing);
                assertEquals(List.of(directory.resolve("Example.txt")), harness.openedFiles());
                return null;
            });
        }
    }

    private static void dispatchRowDoubleClick(JTree tree, TreePath path, boolean trailing) {
        Rectangle bounds = tree.getPathBounds(path); assertNotNull(bounds);
        int x = trailing ? tree.getWidth() - 20 : bounds.x + bounds.width / 2;
        if (trailing) assertTrue(x >= bounds.x + bounds.width);
        int y = bounds.y + bounds.height / 2;
        long when = System.currentTimeMillis();
        for (int click = 1; click <= 2; click++) {
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, when++, MouseEvent.BUTTON1_DOWN_MASK, x, y, click, false, MouseEvent.BUTTON1));
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, when++, 0, x, y, click, false, MouseEvent.BUTTON1));
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, when++, 0, x, y, click, false, MouseEvent.BUTTON1));
        }
    }

    private static Harness createHarness(Map<Path, List<WorkspaceEntry>> listings) throws Exception {
        ManualExecutor executor = new ManualExecutor();
        WorkspaceFiles files = new ListedWorkspaceFiles(listings);
        List<Path> openedFiles = new ArrayList<>();
        return onEdt(() -> {
            ProjectTreeView view = new ProjectTreeView(EditorTheme.graphite(), files, executor,
                    openedFiles::add, message -> { });
            JScrollPane scrollPane = (JScrollPane) view.component();
            JTree tree = (JTree) scrollPane.getViewport().getView();
            return new Harness(view, tree, executor, openedFiles);
        });
    }

    private static WorkspaceEntry directory(Path path) {
        return new WorkspaceEntry(path, path.getFileName().toString(), true);
    }

    private static WorkspaceEntry file(Path path) {
        return new WorkspaceEntry(path, path.getFileName().toString(), false);
    }

    private static void dispatchDoubleClick(JTree tree, TreePath path) {
        Rectangle bounds = tree.getPathBounds(path);
        assertNotNull(bounds);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 1L, 0,
                bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, 2, false, MouseEvent.BUTTON1));
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> null);
    }

    private record Harness(ProjectTreeView view, JTree tree, ManualExecutor executor, List<Path> openedFiles) {
    }

    /** Runs requested I/O in a deliberate order, with no timing assumptions or sleeps. */
    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.addLast(task);
        }

        private void runFirst() {
            tasks.removeFirst().run();
        }

        private void runLast() {
            tasks.removeLast().run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class ListedWorkspaceFiles implements WorkspaceFiles {
        private final Map<Path, List<WorkspaceEntry>> listings;

        private ListedWorkspaceFiles(Map<Path, List<WorkspaceEntry>> listings) {
            this.listings = listings;
        }

        @Override
        public Workspace openWorkspace(Path root) {
            return new Workspace(root);
        }

        @Override
        public List<WorkspaceEntry> list(Workspace workspace, Path directory) throws IOException {
            List<WorkspaceEntry> entries = listings.get(directory);
            if (entries == null) throw new IOException("No test listing for " + directory);
            return entries;
        }

        @Override
        public TextFile read(Workspace workspace, Path file) throws IOException {
            throw new IOException("This test filesystem supplies directory listings only");
        }
    }
}
