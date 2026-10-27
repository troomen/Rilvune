package dev.rilvune.workspace.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.workspace.domain.Workspace;
import dev.rilvune.workspace.domain.WorkspaceEntry;
import dev.rilvune.workspace.domain.WorkspaceFiles;
import dev.rilvune.platform.ui.Glyph;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FileStatusColors;
import dev.rilvune.platform.decoration.PathDecoration;
import dev.rilvune.platform.decoration.PathDecorations;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Lazy filesystem tree. All public view methods are called on the EDT. */
public final class ProjectTreeView {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".cache", ".local", ".idea", "build", "node_modules");
    private final WorkspaceFiles files;
    private final Executor ioExecutor;
    private final Consumer<Path> openFile;
    private final Consumer<String> reportStatus;
    private final JTree tree;
    private final JScrollPane scrollPane;
    private final Map<Path, EntryNode> nodes = new HashMap<>();
    private EditorTheme theme;
    private PathDecorations decorations = PathDecorations.empty();
    private Consumer<Set<Path>> materializedFilesListener = ignored -> { };
    private Workspace workspace;
    private long generation;
    private boolean showExcludedDirectories;
    private Function<Optional<Path>, JPopupMenu> contextMenuProvider = ignored -> null;
    private Consumer<Set<Path>> expandedDirectoriesListener = ignored -> { };

    public ProjectTreeView(EditorTheme theme, WorkspaceFiles files, Executor ioExecutor,
                           Consumer<Path> openFile, Consumer<String> reportStatus) {
        this.theme = theme;
        this.files = files;
        this.ioExecutor = ioExecutor;
        this.openFile = openFile;
        this.reportStatus = reportStatus;
        tree = new JTree(new DefaultMutableTreeNode("Opening project…"));
        tree.setName("project.tree");
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        tree.setShowsRootHandles(true);
        tree.setRootVisible(true);
        tree.setToggleClickCount(2);
        tree.setTransferHandler(new javax.swing.TransferHandler() {
            @Override public int getSourceActions(JComponent component) { return COPY; }
            @Override protected java.awt.datatransfer.Transferable createTransferable(JComponent component) {
                TreePath[] selection = tree.getSelectionPaths();
                if (selection == null) return null;
                List<java.io.File> selected = java.util.Arrays.stream(selection).map(TreePath::getLastPathComponent)
                        .filter(EntryNode.class::isInstance).map(EntryNode.class::cast).filter(node -> !node.entry.directory()).map(node -> node.entry.path().toFile()).toList();
                if (selected.isEmpty()) return null;
                return new java.awt.datatransfer.Transferable() {
                    public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.javaFileListFlavor }; }
                    public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) { return flavor.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor); }
                    public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) throws java.awt.datatransfer.UnsupportedFlavorException {
                        if (!isDataFlavorSupported(flavor)) throw new java.awt.datatransfer.UnsupportedFlavorException(flavor); return selected;
                    }
                };
            }
        });
        if (!java.awt.GraphicsEnvironment.isHeadless()) tree.setDragEnabled(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            { putClientProperty("html.disable", Boolean.TRUE); }
            @Override
            public Component getTreeCellRendererComponent(JTree source, Object value, boolean selected,
                                                          boolean expanded, boolean leaf, int row, boolean focused) {
                super.getTreeCellRendererComponent(source, value, selected, expanded, leaf, row, focused);
                EditorTheme current = ProjectTreeView.this.theme;
                UiTypography.interfaceFont(this, current);
                setBackgroundNonSelectionColor(current.surface());
                setBackgroundSelectionColor(current.selection());
                setTextNonSelectionColor(current.text());
                setTextSelectionColor(current.text());
                setBorderSelectionColor(null);
                setToolTipText(null); getAccessibleContext().setAccessibleDescription("");
                if (value instanceof EntryNode node) {
                    PathDecoration decoration = !node.entry.directory() && workspace != null && workspace.root().equals(decorations.root()) ? decorations.paths().get(node.entry.path()) : null;
                    setForeground(decoration == null ? current.text() : FileStatusColors.color(decoration.tone(), selected));
                    String description = decoration == null ? decorations.availability() : decoration.description();
                    if (!description.isEmpty()) { setToolTipText("Status: " + description); getAccessibleContext().setAccessibleDescription(description); }
                    setText(node.entry.name());
                    setIcon(new Glyph(node.entry.directory() ? Glyph.Kind.FOLDER : Glyph.Kind.FILE,
                            current.text(), 16));
                }
                return this;
            }
        });
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof EntryNode node && !node.loaded) load(node, false);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override public void treeExpanded(TreeExpansionEvent event) { publishExpandedDirectories(); }
            @Override public void treeCollapsed(TreeExpansionEvent event) { publishExpandedDirectories(); }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                TreePath path = rowAt(event);
                if (SwingUtilities.isLeftMouseButton(event) && path != null
                        && !event.isShiftDown() && !event.isControlDown() && !event.isMetaDown() && !tree.isPathSelected(path)) {
                    tree.setSelectionPath(path);
                }
                showPopup(event);
            }
            @Override public void mouseReleased(MouseEvent event) { showPopup(event); }
            @Override public void mouseClicked(MouseEvent event) {
                TreePath path = rowAt(event);
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event) && path != null
                        && path.getLastPathComponent() instanceof EntryNode node) {
                    if (!node.entry.directory()) openFile.accept(node.entry.path());
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-file");
        tree.getActionMap().put("open-file", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { openSelection(); }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("shift F10"), "context-menu");
        tree.getActionMap().put("context-menu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                JPopupMenu menu = contextMenuForSelection();
                Rectangle bounds = tree.getSelectionPath() == null ? null : tree.getPathBounds(tree.getSelectionPath());
                if (menu != null) menu.show(tree, bounds == null ? 10 : bounds.x, bounds == null ? 10 : bounds.y + bounds.height);
            }
        });
        scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        applyTheme(theme);
    }

    public void setMaterializedFilesListener(Consumer<Set<Path>> listener) { materializedFilesListener = listener; publishMaterializedFiles(); }
    private void publishMaterializedFiles() {
        materializedFilesListener.accept(nodes.values().stream().filter(node -> !node.entry.directory()).map(node -> node.entry.path()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
    public void setDecorations(PathDecorations value) {
        PathDecorations previous = decorations; decorations = value;
        if (previous.equals(value)) return;
        for (Map.Entry<Path, EntryNode> entry : nodes.entrySet()) {
            if (java.util.Objects.equals(previous.paths().get(entry.getKey()), value.paths().get(entry.getKey()))
                    && previous.availability().equals(value.availability()) && java.util.Objects.equals(previous.root(), value.root())) continue;
            Rectangle bounds = tree.getPathBounds(new TreePath(entry.getValue().getPath()));
            if (bounds != null) tree.repaint(0, bounds.y, tree.getWidth(), bounds.height);
        }
    }
    public JComponent component() { return scrollPane; }

    public void applyTheme(EditorTheme value) {
        theme = value;
        UiTypography.apply(scrollPane, theme);
        tree.setBackground(theme.surface());
        tree.setForeground(theme.text());
        tree.setRowHeight(Math.max(26, theme.interfaceFont().getSize() + 12));
        scrollPane.getViewport().setBackground(theme.surface());
        tree.revalidate();
        tree.repaint();
    }

    public Optional<Path> selectedPath() {
        return tree.getLastSelectedPathComponent() instanceof EntryNode node ? Optional.of(node.entry.path()) : Optional.empty();
    }

    public void setContextMenuProvider(Function<Optional<Path>, JPopupMenu> provider) { contextMenuProvider = provider; }

    /** Also used by keyboard interaction and accessibility tests. */
    public JPopupMenu contextMenuForSelection() { return contextMenuProvider.apply(selectedPath()); }

    public void setExpandedDirectoriesListener(Consumer<Set<Path>> listener) {
        expandedDirectoriesListener = listener;
        publishExpandedDirectories();
    }

    public Set<Path> expandedDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        if (workspace == null) return Set.of();
        directories.add(workspace.root());
        EntryNode root = nodes.get(workspace.root());
        if (root != null) {
            Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(root.getPath()));
            if (paths != null) while (paths.hasMoreElements()) {
                Object last = paths.nextElement().getLastPathComponent();
                if (last instanceof EntryNode node && node.entry.directory()) directories.add(node.entry.path());
            }
        }
        return Set.copyOf(directories);
    }

    public void setShowExcludedDirectories(boolean value) {
        showExcludedDirectories = value;
        refreshPreservingState();
    }

    public CompletableFuture<Void> showWorkspace(Workspace value) {
        workspace = value;
        generation++;
        nodes.clear();
        publishMaterializedFiles();
        String name = value.root().getFileName() == null ? value.root().toString() : value.root().getFileName().toString();
        EntryNode root = new EntryNode(new WorkspaceEntry(value.root(), name, true));
        nodes.put(root.entry.path(), root);
        tree.setModel(new DefaultTreeModel(root));
        tree.setSelectionRow(0);
        publishExpandedDirectories();
        return load(root, false);
    }

    public void clear() {
        workspace = null;
        generation++;
        nodes.clear();
        publishMaterializedFiles();
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("No project open")));
        publishExpandedDirectories();
    }

    public CompletableFuture<Void> refreshPreservingState() { return refreshBranches(expandedDirectories()); }

    public CompletableFuture<Void> refreshBranches(Set<Path> directories) {
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (Path directory : directories) {
            EntryNode node = nodes.get(directory.toAbsolutePath().normalize());
            if (node != null && node.entry.directory() && (node.loaded || node.getParent() == null)) loads.add(load(node, true));
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    public CompletableFuture<Void> select(Path requested) {
        if (workspace == null) return CompletableFuture.completedFuture(null);
        Path path = (requested.isAbsolute() ? requested : workspace.root().resolve(requested)).toAbsolutePath().normalize();
        if (!path.startsWith(workspace.root())) return CompletableFuture.completedFuture(null);
        return selectDescendant(nodes.get(workspace.root()), path, generation);
    }

    private CompletableFuture<Void> selectDescendant(EntryNode node, Path requested, long requestedGeneration) {
        if (node == null || generation != requestedGeneration) return CompletableFuture.completedFuture(null);
        TreePath treePath = new TreePath(node.getPath());
        if (node.entry.path().equals(requested)) {
            tree.setSelectionPath(treePath);
            tree.scrollPathToVisible(treePath);
            return CompletableFuture.completedFuture(null);
        }
        if (!node.entry.directory()) return CompletableFuture.completedFuture(null);
        tree.expandPath(treePath);
        return load(node, false).thenCompose(ignored -> {
            if (generation != requestedGeneration) return CompletableFuture.completedFuture(null);
            for (int index = 0; index < node.getChildCount(); index++) {
                if (node.getChildAt(index) instanceof EntryNode child && requested.startsWith(child.entry.path())) {
                    return selectDescendant(child, requested, requestedGeneration);
                }
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    public void collapse() {
        for (int row = tree.getRowCount() - 1; row > 0; row--) tree.collapseRow(row);
    }

    private CompletableFuture<Void> load(EntryNode node, boolean refresh) {
        if (node.loading != null && !node.loading.isDone()) return node.loading;
        if (node.loaded && !refresh) return CompletableFuture.completedFuture(null);
        long requestedGeneration = generation;
        Workspace requestedWorkspace = workspace;
        CompletableFuture<Void> displayed = new CompletableFuture<>();
        node.loading = displayed;
        CompletableFuture.supplyAsync(() -> {
            try { return files.list(requestedWorkspace, node.entry.path()); }
            catch (java.io.IOException exception) { throw new CompletionException(exception); }
        }, ioExecutor).whenComplete((entries, error) -> SwingUtilities.invokeLater(() -> {
            if (generation != requestedGeneration || nodes.get(node.entry.path()) != node) { displayed.complete(null); return; }
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                reportStatus.accept("Cannot list directory: " + cause.getMessage());
                displayed.completeExceptionally(cause);
                return;
            }
            List<WorkspaceEntry> visibleEntries = entries.stream()
                    .filter(entry -> showExcludedDirectories || !entry.directory() || !EXCLUDED_DIRECTORIES.contains(entry.name())).toList();
            if (node.loaded && node.getChildCount() == visibleEntries.size()) {
                boolean unchanged = true;
                for (int index = 0; index < visibleEntries.size(); index++) {
                    if (!(node.getChildAt(index) instanceof EntryNode child) || !child.entry.equals(visibleEntries.get(index))) {
                        unchanged = false; break;
                    }
                }
                if (unchanged) { displayed.complete(null); return; }
            }
            Set<Path> expanded = new HashSet<>(expandedDirectories());
            expanded.removeIf(path -> nodes.get(path) == null || !tree.isExpanded(new TreePath(nodes.get(path).getPath())));
            Optional<Path> selected = selectedPath();
            boolean nodeExpanded = tree.isExpanded(new TreePath(node.getPath()));
            Map<Path, EntryNode> previousChildren = new HashMap<>();
            for (int index = 0; index < node.getChildCount(); index++) {
                if (node.getChildAt(index) instanceof EntryNode child) previousChildren.put(child.entry.path(), child);
            }
            node.removeAllChildren();
            for (WorkspaceEntry entry : visibleEntries) {
                EntryNode child = previousChildren.remove(entry.path());
                if (child == null || child.entry.directory() != entry.directory()) {
                    if (child != null) removeIndex(child);
                    child = new EntryNode(entry);
                }
                node.add(child);
                nodes.put(entry.path(), child);
            }
            previousChildren.values().forEach(this::removeIndex);
            node.loaded = true;
            ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(node);
            // Respect a collapse made while the asynchronous listing was running.
            if (nodeExpanded) tree.expandPath(new TreePath(node.getPath()));
            else tree.collapsePath(new TreePath(node.getPath()));
            expanded.stream().sorted(java.util.Comparator.comparingInt(Path::getNameCount)).forEach(path -> {
                EntryNode existing = nodes.get(path);
                if (existing != null && !path.equals(node.entry.path())) tree.expandPath(new TreePath(existing.getPath()));
            });
            selected.map(nodes::get).ifPresent(existing -> tree.setSelectionPath(new TreePath(existing.getPath())));
            publishExpandedDirectories(); publishMaterializedFiles();
            displayed.complete(null);
        }));
        return displayed;
    }

    private void removeIndex(EntryNode node) {
        nodes.remove(node.entry.path());
        for (int index = 0; index < node.getChildCount(); index++) {
            if (node.getChildAt(index) instanceof EntryNode child) removeIndex(child);
        }
    }

    private void publishExpandedDirectories() { expandedDirectoriesListener.accept(expandedDirectories()); }

    private void showPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        TreePath path = rowAt(event);
        if (path == null) tree.clearSelection(); else tree.setSelectionPath(path);
        JPopupMenu menu = contextMenuForSelection();
        if (menu != null) menu.show(tree, event.getX(), event.getY());
    }

    /** Hit the visible row, including space beside the label, but never the empty area below it. */
    private TreePath rowAt(MouseEvent event) {
        if (event.getX() < 0 || event.getX() >= tree.getWidth()) return null;
        TreePath path = tree.getClosestPathForLocation(event.getX(), event.getY());
        Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && event.getY() >= bounds.y && event.getY() < bounds.y + bounds.height ? path : null;
    }

    private void openSelection() {
        if (tree.getLastSelectedPathComponent() instanceof EntryNode node) {
            if (node.entry.directory()) {
                TreePath path = new TreePath(node.getPath());
                if (tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path);
            } else openFile.accept(node.entry.path());
        }
    }

    private static final class EntryNode extends DefaultMutableTreeNode {
        private final WorkspaceEntry entry;
        private boolean loaded;
        private CompletableFuture<Void> loading;
        private EntryNode(WorkspaceEntry entry) {
            super(entry.name());
            this.entry = entry;
            if (entry.directory()) add(new DefaultMutableTreeNode("Loading…"));
        }
    }
}
