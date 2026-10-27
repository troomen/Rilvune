package dev.rilvune.image.ui;

import dev.rilvune.editor.ui.EditorTabs;
import dev.rilvune.image.domain.ImageTabState;
import dev.rilvune.image.infrastructure.*;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.workspace.application.WorkspaceMutationCoordinator;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

/** Window-scoped composition; one decoder/queue/budget survives project transitions. */
public final class ImageTabs implements AutoCloseable {
    private final EditorTabs tabs;
    private final ImageFileDecoder decoder;
    private final Executor workers;
    private final Supplier<EditorTheme> theme;
    private final Map<Path, ImageTabController> byPath = new HashMap<>();
    private final ThreadPoolExecutor decodeExecutor;
    private final Deque<ImageTabController> deferredRestores = new ArrayDeque<>();
    private long epoch;
    private boolean paused;
    public ImageTabs(EditorTabs tabs, Path browseRoot, Executor workers, Supplier<EditorTheme> theme) {
        this(tabs, new ImageFileLoader(browseRoot, ImageFileLoader.Limits.standard(), ImageMemoryBudget.standard()), workers, theme);
    }
    public ImageTabs(EditorTabs tabs, ImageFileDecoder decoder, Executor workers, Supplier<EditorTheme> theme) {
        this.tabs = tabs; this.decoder = decoder; this.workers = workers; this.theme = theme;
        decodeExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            var thread = new Thread(runnable, "image-decoder"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public CompletableFuture<Void> open(Path canonicalPath, String id, boolean activate) { return open(canonicalPath, id, () -> activate); }
    public CompletableFuture<Void> open(Path canonicalPath, String id, BooleanSupplier activate) {
        if (paused) return CompletableFuture.completedFuture(null);
        long requestEpoch = epoch;
        var existing = byPath.get(canonicalPath);
        if (existing != null) {
            var closing = tabs.pendingClose(existing.model().id());
            if (closing.isPresent()) return closing.get().handleAsync((accepted, failure) -> {
                if (paused || requestEpoch != epoch) return CompletableFuture.<Void>completedFuture(null);
                return open(canonicalPath, id, activate);
            }, SwingUtilities::invokeLater).thenCompose(operation -> operation);
            if (activate.getAsBoolean()) tabs.select(existing.model().id()); return CompletableFuture.completedFuture(null);
        }
        var controller = new ImageTabController(this, id, canonicalPath); byPath.put(canonicalPath, controller);
        try { tabs.register(controller.view(), activate.getAsBoolean()); return controller.reload(); }
        catch (RuntimeException failure) { controller.dispose(); throw failure; }
    }
    /** Restore descriptors immediately; unadmitted descriptors hold no jobs, streams or pixel memory. */
    public String restore(Path canonicalPath, String id) {
        var existing = byPath.get(canonicalPath);
        if (existing != null) return existing.model().id();
        var controller = new ImageTabController(this, id, canonicalPath); byPath.put(canonicalPath, controller);
        try {
            tabs.register(controller.view(), false);
            deferredRestores.addLast(controller); decodeFinished(); return id;
        } catch (RuntimeException failure) { controller.dispose(); throw failure; }
    }
    void cancelRestore(ImageTabController controller) { deferredRestores.remove(controller); }
    void decodeFinished() {
        while (!paused && decodeExecutor.getQueue().remainingCapacity() > 0 && !deferredRestores.isEmpty()) {
            var controller = deferredRestores.removeFirst();
            if (!controller.disposed()) controller.reload();
        }
    }
    void removed(ImageTabController controller) {
        deferredRestores.remove(controller); byPath.values().removeIf(value -> value == controller); decodeFinished();
    }
    public void checkActive() { tabs.activeTab().filter(ImageTabView.class::isInstance).map(ImageTabView.class::cast).ifPresent(view -> view.controller().checkForChanges()); }
    public void mutation(WorkspaceMutationCoordinator.Kind kind, Path source, WorkspaceMutationCoordinator.Result result) {
        if (!result.diskChanged() || kind == WorkspaceMutationCoordinator.Kind.COPY) return;
        for (var controller : List.copyOf(byPath.values())) {
            Path path = controller.model().state().path(); if (!path.startsWith(source)) continue;
            if (kind == WorkspaceMutationCoordinator.Kind.MOVE && result.completed() && result.destination() != null) {
                Path target = result.destination().resolve(source.relativize(path));
                byPath.remove(path, controller);
                // Destination collisions cannot normally occur through WorkspaceOperations; retain one canonical owner.
                var collision = byPath.get(target);
                if (collision != null && collision != controller) tabs.removeTab(collision.model().id());
                byPath.put(target, controller); controller.remap(target);
            } else controller.checkForChanges();
            if (kind == WorkspaceMutationCoordinator.Kind.DELETE && result.completed()) controller.missing();
        }
        tabs.refreshPresentations();
    }
    public CompletableFuture<Void> quiesce() { paused = true; epoch++; List.copyOf(byPath.values()).forEach(ImageTabController::quiesce); return CompletableFuture.completedFuture(null); }
    public void resume() {
        if (!paused) return;
        paused = false;
        for (var controller : List.copyOf(byPath.values())) {
            if (controller.model().state().phase() == ImageTabState.Phase.LOADING && !deferredRestores.contains(controller)) deferredRestores.addLast(controller);
        }
        decodeFinished();
    }
    @Override public void close() { quiesce(); for (var controller : List.copyOf(byPath.values())) controller.dispose(); decodeExecutor.shutdownNow(); }
    long epoch() { return epoch; }
    boolean paused() { return paused; }
    EditorTabs tabs() { return tabs; }
    EditorTheme theme() { return theme.get(); }
    ImageFileDecoder decoder() { return decoder; }
    ThreadPoolExecutor decodeExecutor() { return decodeExecutor; }
    Executor workers() { return workers; }
}
