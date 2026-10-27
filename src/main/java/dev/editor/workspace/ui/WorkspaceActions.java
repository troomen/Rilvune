package dev.editor.workspace.ui;

import dev.editor.document.application.DocumentService;
import dev.editor.document.application.DocumentSession;
import dev.editor.document.ui.EditorView;
import dev.editor.document.ui.TextTabs;
import dev.editor.workspace.application.WorkspaceMutationCoordinator;
import dev.editor.workspace.domain.Workspace;
import dev.editor.workspace.infrastructure.WorkspaceOperations;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.*;

/** Coordinates tree operations with each affected document's persistence lane. */
public final class WorkspaceActions {
    private final Component owner;
    private final Supplier<Workspace> workspace;
    private final WorkspaceOperations operations;
    private final DocumentService documents;
    private final TextTabs tabs;
    private final ProjectTreeView tree;
    private final Executor io;
    private final PathChooser chooser;
    private final Consumer<Path> open;
    private final Function<Path, Action> comparisonActions;
    private final Consumer<String> report;
    private final WorkspaceMutationCoordinator mutations;
    @FunctionalInterface public interface MutationListener { void changed(WorkspaceMutationCoordinator.Kind kind, Path source, WorkspaceMutationCoordinator.Result result); }
    private MutationListener mutationListener = (kind, source, result) -> { };
    public void setMutationListener(MutationListener listener) { mutationListener = java.util.Objects.requireNonNull(listener); }
    private Path clipboard;
    private boolean cut;
    private boolean busy;

    public WorkspaceActions(Component owner, Supplier<Workspace> workspace, WorkspaceOperations operations,
                            DocumentService documents, TextTabs tabs, ProjectTreeView tree, Executor io,
                            PathChooser chooser, Consumer<Path> open, Function<Path, Action> comparisonActions, Consumer<String> report) {
        this.owner = owner; this.workspace = workspace; this.operations = operations; this.documents = documents;
        this.tabs = tabs; this.tree = tree; this.io = io; this.chooser = chooser; this.open = open; this.comparisonActions = comparisonActions; this.report = report;
        this.mutations = new WorkspaceMutationCoordinator(io);
    }
    public boolean busy() { return busy; }
    public JPopupMenu menu(Optional<Path> selection) {
        JPopupMenu menu = new JPopupMenu(); Workspace current = workspace.get(); if (current == null) return menu;
        Path selected = selection.orElse(current.root());
        item(menu, "New file…", () -> create(selected, false), !busy); item(menu, "New directory…", () -> create(selected, true), !busy);
        menu.addSeparator(); item(menu, "Open", () -> open.accept(selected), !Files.isDirectory(selected) && !busy);
        Action comparison = comparisonActions.apply(selected);
        if (Files.isDirectory(selected) || busy) comparison.setEnabled(false);
        menu.add(comparison);
        boolean mutable = !selected.equals(current.root()) && !busy;
        item(menu, "Rename…", () -> rename(selected), mutable); item(menu, "Move to…", () -> moveTo(selected), mutable);
        item(menu, "Copy", () -> { clipboard = selected; cut = false; report.accept("Copied " + selected.getFileName()); }, mutable);
        item(menu, "Cut", () -> { clipboard = selected; cut = true; report.accept("Cut " + selected.getFileName()); }, mutable);
        item(menu, "Paste", () -> paste(selected), clipboard != null && !busy);
        menu.addSeparator(); item(menu, "Delete…", () -> delete(selected), mutable);
        menu.addSeparator(); item(menu, "Copy path", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected.toString()), null), true);
        return menu;
    }
    public void createSelected(boolean directory) {
        if (!busy && workspace.get() != null) create(tree.selectedPath().orElse(workspace.get().root()), directory);
    }
    public void renameSelected() { if (!busy && workspace.get() != null) tree.selectedPath().filter(path -> !path.equals(workspace.get().root())).ifPresent(this::rename); }
    public void deleteSelected() { if (!busy && workspace.get() != null) tree.selectedPath().filter(path -> !path.equals(workspace.get().root())).ifPresent(this::delete); }
    private Path parent(Path selected) { return Files.isDirectory(selected) ? selected : selected.getParent(); }
    private void create(Path selected, boolean directory) {
        String name = JOptionPane.showInputDialog(owner, directory ? "Directory name:" : "File name:", directory ? "New directory" : "New file", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        Path target = child(parent(selected), name); if (target == null) return;
        run(() -> directory ? operations.createDirectory(target) : operations.createFile(target), created -> {
            tree.refreshPreservingState().thenRunAsync(() -> tree.select(created), SwingUtilities::invokeLater);
            if (!directory) open.accept(created); report.accept("Created " + created);
        });
    }
    private void rename(Path source) {
        String name = JOptionPane.showInputDialog(owner, "New name:", source.getFileName().toString());
        if (name == null) return; Path target = child(source.getParent(), name);
        if (target != null && !source.equals(target)) mutate(source, target, false, false);
    }
    private void moveTo(Path source) { chooser.choose(owner, source.getParent(), true, false, "Move to directory").ifPresent(directory -> mutate(source, directory.resolve(source.getFileName()), false, false)); }
    private void paste(Path selected) {
        if (clipboard == null) return;
        Path target = parent(selected).resolve(clipboard.getFileName());
        if (!cut && target.equals(clipboard)) {
            Path source = clipboard;
            run(() -> operations.availableCopyTarget(source), destination -> mutate(source, destination, true, false));
            return;
        }
        if (target.equals(clipboard) || Files.exists(target)) {
            String name = JOptionPane.showInputDialog(owner, "Destination already exists. Choose a new name:", clipboard.getFileName().toString());
            if (name == null) return; target = child(parent(selected), name); if (target == null) return;
        }
        mutate(clipboard, target, !cut, false);
    }
    private void delete(Path source) {
        if (JOptionPane.showConfirmDialog(owner, "Permanently delete “" + source.getFileName() + "”?\nThis also deletes all entries in a directory.",
                "Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION)
            mutate(source, null, false, true);
    }
    private void mutate(Path source, Path target, boolean copy, boolean delete) {
        if (busy || workspace.get() == null || source.equals(workspace.get().root())) return;
        busy = true; report.accept((delete ? "Deleting" : copy ? "Copying" : "Moving") + "…");
        List<DocumentSession> affected = documents.sessions().stream().filter(document -> document.path() != null && document.path().startsWith(source)).toList();
        List<EditorView> affectedViews = tabs.allViews().stream().filter(view -> affected.contains(view.session())).toList();
        affectedViews.forEach(view -> view.setEditingEnabled(false));
        WorkspaceMutationCoordinator.Kind kind = delete ? WorkspaceMutationCoordinator.Kind.DELETE
                : copy ? WorkspaceMutationCoordinator.Kind.COPY : WorkspaceMutationCoordinator.Kind.MOVE;
        mutations.execute(kind, source, target, affected, () -> {
            if (delete) operations.delete(source);
            else if (copy) operations.copy(source, target);
            else operations.move(source, target);
        }).whenCompleteAsync((result, unexpectedFailure) -> {
            busy = false;
            for (EditorView view : affectedViews) {
                if (view.session().isClosed()) tabs.remove(view);
                else view.setEditingEnabled(true);
            }
            if (result != null) mutationListener.changed(kind, source, result);
            tabs.refreshPresentations();
            if (unexpectedFailure != null) error(unexpectedFailure);
            else {
                if (result.diskChanged() && !copy) clipboard = null;
                if (result.failure() != null) error(result.failure());
                else report.accept(delete ? "Deleted" : (copy ? "Copied to " : "Moved to ") + result.destination());
            }
            tree.refreshPreservingState().thenRunAsync(() -> {
                if (target != null) tree.select(target);
            }, SwingUtilities::invokeLater);
        }, SwingUtilities::invokeLater);
    }

    private Path child(Path parent, String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains("\0")) { report.accept("Enter a single file or directory name."); return null; }
        return parent.resolve(name);
    }
    private <T> void run(IoSupplier<T> task, Consumer<T> completed) {
        if (busy) return; busy = true; report.accept("Working…");
        CompletableFuture.supplyAsync(() -> { try { return task.get(); } catch (IOException exception) { throw new CompletionException(exception); } }, io)
                .whenCompleteAsync((result, failure) -> { busy = false; if (failure != null) error(failure); else completed.accept(result); }, SwingUtilities::invokeLater);
    }
    private void error(Throwable failure) { while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) failure = failure.getCause(); report.accept(failure.getMessage()); JOptionPane.showMessageDialog(owner, failure.getMessage(), "File operation failed", JOptionPane.ERROR_MESSAGE); }
    private void item(JPopupMenu menu, String label, Runnable action, boolean enabled) { JMenuItem item = new JMenuItem(label); item.setEnabled(enabled); item.addActionListener(event -> action.run()); menu.add(item); }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
}
