package dev.editor.workspace.application;

import dev.editor.document.application.DocumentHandle;
import dev.editor.document.application.DocumentService;
import dev.editor.document.domain.DocumentChange;
import dev.editor.document.domain.DocumentSnapshot;
import dev.editor.document.domain.SyncState;
import dev.editor.platform.text.domain.LiteralText;
import dev.editor.workspace.domain.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Project-scoped consumers of existing live documents. No UI or agent protocol is exposed. */
public final class ProjectDocuments {
    public enum Rejection { NONE, STALE_PROJECT, STALE_DOCUMENT, CLOSED, PAUSED, NEEDS_RESOLUTION,
        NEEDS_ATTENTION, OUTSIDE_PROJECT, LIMIT_EXCEEDED, BUSY, INVALID_TEXT, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Rejection reason;
        private Failure(Rejection reason, String message) { super(message); this.reason = reason; }
        public Rejection reason() { return reason; }
    }
    /** Opaque version belongs to a particular retained consumer, not merely a persisted UUID. */
    public static final class Version {
        private final Object owner;
        private final long revision;
        private final Path path;
        private Version(ScopedDocument owner, DocumentSnapshot snapshot) {
            this.owner = owner.identity; revision = snapshot.revision(); path = snapshot.path();
        }
        public long revision() { return revision; }
        public Path path() { return path; }
    }
    public record Snapshot(Version version, DocumentSnapshot document) { }
    public record ChangeResult(Rejection rejection, Version version, DocumentChange change,
                               SyncState sourceState, String recoveryError, Throwable persistenceFailure) {
        public boolean accepted() { return change != null && change.accepted(); }
    }

    private final DocumentService documents;
    private final WorkspaceFiles files;
    private final Executor control, io;
    private final int maximumDocuments, maximumCharacters;
    private final Set<ScopedDocument> leases = new HashSet<>();
    private final Set<CompletableFuture<Void>> arrivals = new HashSet<>();
    private ProjectContext context;
    private int slots;
    private long retainedCharacters, applyingCharacters;

    public ProjectDocuments(DocumentService documents, WorkspaceFiles files, Executor control, Executor io) {
        this(documents, files, control, io, 16, 32 * 1024 * 1024);
    }
    public ProjectDocuments(DocumentService documents, WorkspaceFiles files, Executor control, Executor io,
                            int maximumDocuments, int maximumCharacters) {
        if (maximumDocuments < 1 || maximumCharacters < 1) throw new IllegalArgumentException("Positive document budgets required");
        this.documents = Objects.requireNonNull(documents); this.files = Objects.requireNonNull(files);
        this.control = Objects.requireNonNull(control); this.io = Objects.requireNonNull(io);
        this.maximumDocuments = maximumDocuments; this.maximumCharacters = maximumCharacters;
    }

    /** Called by project lifecycle on the same control executor used for admission and edits. */
    public synchronized void resume(ProjectContext next) {
        if (!leases.isEmpty() || !arrivals.isEmpty()) throw failure(Rejection.BUSY, "Finish project document cleanup before resuming");
        context = next;
    }

    public CompletableFuture<ScopedDocument> open(ProjectContext requested, Path relativePath) {
        CompletableFuture<ScopedDocument> result = new CompletableFuture<>();
        onControl(() -> {
            CompletableFuture<Void> settled = new CompletableFuture<>();
            synchronized (this) {
                requireContext(requested);
                if (relativePath.isAbsolute() || !requested.root().resolve(relativePath).normalize().startsWith(requested.root()))
                    throw failure(Rejection.OUTSIDE_PROJECT, "Choose a relative path inside the project");
                if (slots >= maximumDocuments || retainedCharacters + applyingCharacters >= maximumCharacters)
                    throw failure(Rejection.LIMIT_EXCEEDED, "Project document budget reached; release unused documents");
                slots++; arrivals.add(settled);
            }
            resolve(requested, relativePath).thenCompose(documents::acquire).whenComplete((handle, error) -> {
                if (error != null) { finishArrival(settled, result, null, error, null); return; }
                ScopedDocument lease = new ScopedDocument(requested, handle);
                synchronized (this) { leases.add(lease); }
                onControl(() -> {
                    synchronized (this) {
                        requireContext(requested);
                        if (!handle.session().path().startsWith(requested.root())) throw failure(Rejection.OUTSIDE_PROJECT, "Acquired file left the project");
                        lease.capture();
                    }
                    return lease;
                }).whenComplete((ready, failure) -> {
                    if (failure == null) { finishArrival(settled, result, lease, null, null); return; }
                    lease.release().whenComplete((ignored, cleanup) -> {
                        if (cleanup != null) failure.addSuppressed(cleanup);
                        finishArrival(settled, result, lease, failure, cleanup);
                    });
                });
            });
            return null;
        }).exceptionally(error -> { result.completeExceptionally(root(error)); return null; });
        CompletableFuture<ScopedDocument> observer = result.copy();
        observer.whenComplete((ignored, error) -> {
            if (observer.isCancelled()) result.thenAccept(consumer -> consumer.release().exceptionally(failure -> {
                System.getLogger(ProjectDocuments.class.getName()).log(System.Logger.Level.WARNING, "Cancelled read cleanup failed", root(failure));
                return null;
            }));
        });
        return observer;
    }

    private void finishArrival(CompletableFuture<Void> settled, CompletableFuture<ScopedDocument> result,
                                ScopedDocument lease, Throwable error, Throwable cleanup) {
        synchronized (this) {
            arrivals.remove(settled);
            if (lease == null) slots--;
            if (cleanup == null) settled.complete(null); else settled.completeExceptionally(cleanup);
        }
        if (error == null) result.complete(lease); else result.completeExceptionally(root(error));
    }

    public CompletableFuture<Void> quiesce() {
        List<CompletableFuture<Void>> pending;
        List<ScopedDocument> consumers;
        synchronized (this) {
            context = null;
            pending = new ArrayList<>(arrivals);
            consumers = List.copyOf(leases);
        }
        for (ScopedDocument consumer : consumers) pending.add(consumer.release());
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).copy();
    }

    public final class ScopedDocument {
        private final Object identity = new Object();
        private final ProjectContext acquiredContext;
        private final DocumentHandle handle;
        private Snapshot retained;
        private boolean closing, released, applying;
        private int reservedCharacters;
        private CompletableFuture<Void> applied = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> releasing;

        private ScopedDocument(ProjectContext acquiredContext, DocumentHandle handle) {
            this.acquiredContext = acquiredContext; this.handle = handle;
        }
        public Snapshot snapshot() {
            synchronized (ProjectDocuments.this) {
                requireUsable();
                return capture();
            }
        }
        /** Capture only after reserving the exact text length; materialize no oversized outbound snapshot. */
        private Snapshot capture() {
            synchronized (handle.session()) {
                int length = handle.session().textLength();
                int previous = retained == null ? 0 : retained.document().text().length();
                if (retainedCharacters - previous + length + applyingCharacters > maximumCharacters)
                    throw failure(Rejection.LIMIT_EXCEEDED, "Project snapshot character budget exceeded");
                DocumentSnapshot snapshot = handle.session().snapshot();
                retainedCharacters += length - previous;
                retained = new Snapshot(new Version(this, snapshot), snapshot);
                return retained;
            }
        }

        public CompletableFuture<ChangeResult> apply(Version version, String normalizedText) {
            Objects.requireNonNull(normalizedText);
            CompletableFuture<ChangeResult> result = new CompletableFuture<>();
            onControl(() -> {
                synchronized (ProjectDocuments.this) {
                    Rejection rejected = applicationRejection(version, normalizedText);
                    if (rejected != Rejection.NONE) { result.complete(rejected(rejected)); return null; }
                    applying = true;
                    applied = new CompletableFuture<>();
                    reservedCharacters = normalizedText.length();
                    applyingCharacters += reservedCharacters;
                }
                // Filesystem checks never run on the editing executor.
                resolve(acquiredContext, version.path()).whenComplete((path, error) -> {
                    onControl(() -> {
                        if (error != null) {
                            completeApply(result, rejected(pathRejection(error)));
                            return null;
                        }
                        commit(version, normalizedText, path, result);
                        return null;
                    }).exceptionally(dispatchFailure -> {
                        completeApply(result, new ChangeResult(Rejection.UNAVAILABLE, null, null,
                                null, null, root(dispatchFailure))); return null;
                    });
                });
                return null;
            }).exceptionally(error -> { result.completeExceptionally(root(error)); return null; });
            return result.copy();
        }

        private void commit(Version version, String text, Path canonical, CompletableFuture<ChangeResult> result) {
            DocumentChange change;
            Version committed;
            synchronized (ProjectDocuments.this) {
                // The edit was admitted before quiescence. Release waits for this accepted operation.
                if (!canonical.equals(version.path())) {
                    completeApply(result, rejected(Rejection.STALE_DOCUMENT)); return;
                }
                change = handle.session().changeIfCurrent(version.revision(), version.path(), text);
                if (!change.accepted()) {
                    completeApply(result, new ChangeResult(Rejection.valueOf(change.rejection().name()),
                            null, change, null, null, null)); return;
                }
                int before = retained == null ? 0 : retained.document().text().length();
                retainedCharacters += change.snapshot().text().length() - before;
                applyingCharacters -= reservedCharacters; reservedCharacters = 0;
                committed = new Version(this, change.snapshot());
                retained = new Snapshot(committed, change.snapshot());
            }
            // Flush has its own retry path; failure never turns an applied edit into a rejection.
            handle.session().flush().whenComplete((persisted, error) -> {
                DocumentSnapshot latest = persisted == null ? handle.session().snapshot() : persisted;
                completeApply(result, new ChangeResult(Rejection.NONE, committed, change,
                        latest.state(), latest.recoveryError(), root(error)));
            });
        }

        private Rejection applicationRejection(Version version, String text) {
            if (!Objects.equals(context, acquiredContext)) return Rejection.STALE_PROJECT;
            if (closing || released) return Rejection.CLOSED;
            if (applying) return Rejection.BUSY;
            if (version == null || version.owner != identity) return Rejection.STALE_DOCUMENT;
            if (text.indexOf('\r') >= 0) return Rejection.INVALID_TEXT;
            if (text.length() > LiteralText.MAX_RESULT_CHARACTERS || retainedCharacters + applyingCharacters + text.length() > maximumCharacters)
                return Rejection.LIMIT_EXCEEDED;
            return Rejection.NONE;
        }

        private void completeApply(CompletableFuture<ChangeResult> result, ChangeResult outcome) {
            synchronized (ProjectDocuments.this) {
                applyingCharacters -= reservedCharacters; reservedCharacters = 0;
                applying = false;
                applied.complete(null);
            }
            result.complete(outcome);
        }

        public CompletableFuture<Void> release() {
            CompletableFuture<Void> completion;
            CompletableFuture<Void> wait;
            synchronized (ProjectDocuments.this) {
                if (released) return CompletableFuture.completedFuture(null);
                if (releasing != null) return releasing.copy();
                closing = true;
                completion = new CompletableFuture<>(); releasing = completion;
                wait = applied;
            }
            wait.thenCompose(ignored -> handle.release()).whenComplete((ignored, error) -> {
                synchronized (ProjectDocuments.this) {
                    if (error == null) {
                        released = true; leases.remove(this); slots--;
                        if (retained != null) retainedCharacters -= retained.document().text().length();
                        retained = null;
                    } else releasing = null;
                }
                if (error == null) completion.complete(null); else completion.completeExceptionally(root(error));
            });
            return completion.copy();
        }
        private void requireUsable() {
            requireContext(acquiredContext);
            if (closing || released || handle.session().isClosed()) throw failure(Rejection.CLOSED, "Document consumer is closed");
        }
    }

    private CompletableFuture<Path> resolve(ProjectContext requested, Path path) {
        return dispatch(() -> {
            try {
                Path canonical = files.canonicalFile(new Workspace(requested.root()), path);
                if (!canonical.startsWith(requested.root())) throw new OutsideWorkspaceException(path);
                return canonical;
            }
            catch (OutsideWorkspaceException failure) { throw failure(Rejection.OUTSIDE_PROJECT, failure.getMessage()); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, io);
    }
    private synchronized void requireContext(ProjectContext requested) {
        if (requested == null || !requested.equals(context)) throw failure(Rejection.STALE_PROJECT, "Project context is no longer active");
    }
    private static ChangeResult rejected(Rejection rejection) { return new ChangeResult(rejection, null, null, null, null, null); }
    private static Failure failure(Rejection rejection, String message) { return new Failure(rejection, message); }
    private static Rejection pathRejection(Throwable error) {
        return root(error) instanceof Failure failure ? failure.reason() : Rejection.UNAVAILABLE;
    }
    private <T> CompletableFuture<T> onControl(Supplier<T> action) { return dispatch(action, control); }
    private static <T> CompletableFuture<T> dispatch(Supplier<T> action, Executor executor) {
        try { return CompletableFuture.supplyAsync(action, executor); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private static Throwable root(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
}
