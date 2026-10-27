package dev.rilvune.app.application;

import dev.rilvune.document.application.DocumentHandle;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Owns each acquisition until presentation accepts it or its consumer is durably released. */
public final class FileOpenCoordinator {
    public record Adoption<T>(T value, boolean transferred) { }
    private final Function<Path, CompletableFuture<DocumentHandle>> acquire;
    private final Executor presentation;
    private final Set<CompletableFuture<Void>> pending = new HashSet<>();
    private final Set<DocumentHandle> cleanup = new HashSet<>();
    private long epoch;
    private boolean accepting = true;

    public FileOpenCoordinator(Function<Path, CompletableFuture<DocumentHandle>> acquire, Executor presentation) {
        this.acquire = Objects.requireNonNull(acquire);
        this.presentation = Objects.requireNonNull(presentation);
    }

    /** An adopter must return ownership on failure and transfer it only on a successful result. */
    public <T> CompletableFuture<T> open(Path path, Function<DocumentHandle, Adoption<T>> adopter) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        CompletableFuture<T> result = new CompletableFuture<>();
        long request;
        synchronized (this) {
            if (!accepting || !cleanup.isEmpty()) return CompletableFuture.failedFuture(
                    new IllegalStateException("Finish pending file-open cleanup before opening files"));
            request = epoch;
            pending.add(settled);
        }
        CompletableFuture<DocumentHandle> loaded;
        try { loaded = Objects.requireNonNull(acquire.apply(path)); }
        catch (RuntimeException failure) { loaded = CompletableFuture.failedFuture(failure); }
        loaded.whenComplete((handle, failure) -> {
            if (failure != null) { finish(settled, result, null, failure, null); return; }
            CompletableFuture<Adoption<T>> shown;
            try {
                shown = CompletableFuture.supplyAsync(() -> {
                    synchronized (this) {
                        return accepting && epoch == request ? Objects.requireNonNull(adopter.apply(handle)) : new Adoption<T>(null, false);
                    }
                }, presentation);
            } catch (RuntimeException rejected) { shown = CompletableFuture.failedFuture(rejected); }
            shown.whenComplete((adoption, error) -> {
                if (error == null && adoption.transferred()) {
                    finish(settled, result, adoption.value(), null, null);
                    return;
                }
                release(handle).whenComplete((ignored, releaseFailure) -> {
                    if (error != null && releaseFailure != null) error.addSuppressed(releaseFailure);
                    finish(settled, result, adoption == null ? null : adoption.value(),
                            error == null ? releaseFailure : error, releaseFailure);
                });
            });
        });
        // Cancelling an observer must never cancel acquisition or lose ownership of a late result.
        return result.copy();
    }

    private <T> void finish(CompletableFuture<Void> settled, CompletableFuture<T> result,
                            T value, Throwable error, Throwable releaseFailure) {
        synchronized (this) {
            pending.remove(settled);
            if (releaseFailure == null) settled.complete(null);
            else settled.completeExceptionally(releaseFailure);
        }
        if (error == null) result.complete(value);
        else result.completeExceptionally(error);
    }

    private CompletableFuture<Void> release(DocumentHandle handle) {
        synchronized (this) { cleanup.add(handle); }
        return handle.release().thenRun(() -> { synchronized (this) { cleanup.remove(handle); } });
    }

    /** Invalidates presentation immediately, drains arrivals and retries earlier cleanup failures. */
    public CompletableFuture<Void> quiesce() {
        List<CompletableFuture<Void>> waits;
        List<DocumentHandle> retry;
        synchronized (this) {
            accepting = false;
            epoch++;
            waits = new ArrayList<>(pending);
            retry = List.copyOf(cleanup);
        }
        for (DocumentHandle handle : retry) waits.add(release(handle));
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).copy();
    }

    public synchronized void resume() { accepting = true; }
}
