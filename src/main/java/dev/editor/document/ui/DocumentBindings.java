package dev.editor.document.ui;

import dev.editor.document.application.DocumentHandle;
import dev.editor.document.application.DocumentService;
import dev.editor.document.application.DocumentSession;

import javax.swing.SwingUtilities;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicit UI scope: deduplicates bindings, while the service owns session lifetime. */
public final class DocumentBindings implements AutoCloseable {
    private final DocumentService documents;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private boolean closed;
    private final java.util.concurrent.Executor searchExecutor;

    public DocumentBindings(DocumentService documents) { this(documents, java.util.concurrent.ForkJoinPool.commonPool()); }
    public DocumentBindings(DocumentService documents, java.util.concurrent.Executor searchExecutor) {
        this.documents = java.util.Objects.requireNonNull(documents);
        this.searchExecutor = java.util.Objects.requireNonNull(searchExecutor);
    }

    public Lease acquire(DocumentSession session) {
        DocumentBinding.requireEdt();
        if (closed) throw new IllegalStateException("Document bindings are closed");
        return adopt(documents.retain(session));
    }
    /** Transfers an already-acquired consumer from background file loading to the UI scope. */
    public Lease adopt(DocumentHandle handle) {
        DocumentBinding.requireEdt();
        if (closed) throw new IllegalStateException("Document bindings are closed");
        if (handle.isReleased()) throw new IllegalArgumentException("Cannot adopt a released document handle");
        DocumentSession session = handle.session();
        Entry entry = entries.get(session.id());
        if (entry == null || entry.binding.session() != session) {
            // Recovery can reopen the same UUID as a new session before old views finish disposal.
            if (entry != null) entry.binding.close();
            entry = new Entry(new DocumentBinding(session, searchExecutor));
            entries.put(session.id(), entry);
        }
        entry.users++;
        return new Lease(entry, handle);
    }

    public final class Lease {
        private final Entry entry;
        private final DocumentHandle handle;
        private CompletableFuture<Void> pending;
        private boolean released;

        private Lease(Entry entry, DocumentHandle handle) { this.entry = entry; this.handle = handle; }
        public DocumentBinding binding() { return entry.binding; }
        public boolean isReleased() { return released; }

        /** Roll back a failed presentation; the caller still owns and must release the handle. */
        void returnOwnership() {
            DocumentBinding.requireEdt();
            if (released || pending != null) throw new IllegalStateException("Lease already released");
            released = true;
            detach();
        }

        private void detach() {
            if (--entry.users == 0) {
                entries.remove(entry.binding.session().id(), entry);
                entry.binding.close();
            }
        }

        public CompletableFuture<Void> release() {
            DocumentBinding.requireEdt();
            if (released) return CompletableFuture.completedFuture(null);
            if (pending != null) return pending;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            pending = completion;
            handle.release().whenCompleteAsync((ignored, failure) -> {
                if (failure != null) {
                    pending = null;
                    completion.completeExceptionally(failure);
                    return;
                }
                released = true;
                try {
                    detach();
                    completion.complete(null);
                } catch (RuntimeException cleanupFailure) {
                    completion.completeExceptionally(cleanupFailure);
                }
            }, SwingUtilities::invokeLater);
            return completion;
        }
    }

    /** Called after the document service has shut down, never as a substitute for durable close. */
    @Override public void close() {
        DocumentBinding.requireEdt();
        if (entries.values().stream().anyMatch(entry -> !entry.binding.session().isClosed())) {
            throw new IllegalStateException("Close document sessions before disposing the UI scope");
        }
        if (closed) return;
        closed = true;
        entries.values().forEach(entry -> entry.binding.close());
        entries.clear();
    }

    private static final class Entry {
        private final DocumentBinding binding;
        private int users;
        private Entry(DocumentBinding binding) { this.binding = binding; }
    }
}
