package dev.editor.document.application;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** An explicit consumer of a session; the final release durably closes the document. */
public final class DocumentHandle {
    private final DocumentSession session;
    private final Supplier<CompletableFuture<Void>> release;
    private boolean released;
    private CompletableFuture<Void> pending;

    DocumentHandle(DocumentSession session, Supplier<CompletableFuture<Void>> release) {
        this.session = session;
        this.release = release;
    }

    public DocumentSession session() { return session; }
    public synchronized boolean isReleased() { return released; }

    public CompletableFuture<Void> release() {
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (released) return CompletableFuture.completedFuture(null);
            if (pending != null) return pending;
            completion = new CompletableFuture<>();
            pending = completion;
        }
        try {
            release.get().whenComplete((ignored, failure) -> {
                synchronized (this) {
                    if (failure == null) released = true;
                    else pending = null;
                }
                if (failure == null) completion.complete(null);
                else completion.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) {
            synchronized (this) { pending = null; }
            completion.completeExceptionally(failure);
        }
        return completion;
    }
}
