package dev.rilvune.workspace.application;

import dev.rilvune.workspace.domain.PartialMoveException;

import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.document.domain.DocumentSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Orders document persistence and filesystem mutation without depending on Swing or NIO adapters. */
public final class WorkspaceMutationCoordinator {
    public enum Kind { MOVE, COPY, DELETE }
    @FunctionalInterface public interface IoOperation { void run() throws IOException; }
    public record Result(boolean diskChanged, boolean completed, Path destination,
                         Set<DocumentSession> closedDocuments, Set<DocumentSession> pausedDocuments,
                         IOException failure) { }

    private final Executor io;

    public WorkspaceMutationCoordinator(Executor io) { this.io = io; }

    /** The UI freezes editing and project/tab lifecycle until this future completes. */
    public CompletableFuture<Result> execute(Kind kind, Path source, Path destination,
                                            List<DocumentSession> affected, IoOperation operation) {
        State state = new State(kind, source, destination, affected);
        for (DocumentSession document : affected) {
            if (document.isClosed() || document.isPaused()) {
                state.failures.add(new IOException("An affected document is closed or already paused; resolve it before moving files."));
                return CompletableFuture.completedFuture(state.result());
            }
        }
        return snapshots(affected.stream().map(DocumentSession::flush).toList())
                .thenCompose(flushed -> {
                    checkReady(flushed, state);
                    state.pauseAttempted.addAll(affected);
                    return snapshots(affected.stream().map(DocumentSession::pause).toList());
                }).thenApply(paused -> {
                    checkReady(paused, state);
                    return paused;
                }).thenRunAsync(() -> {
                    try {
                        operation.run();
                        state.diskChanged = true;
                        state.completed = true;
                    } catch (PartialMoveException partial) {
                        state.diskChanged = true;
                        state.completed = kind == Kind.MOVE;
                        if (kind == Kind.MOVE) state.destination = partial.target();
                        state.failures.add(partial);
                    } catch (IOException failure) { throw new CompletionException(failure); }
                }, io).thenCompose(ignored -> reconcile(state))
                .handle((ignored, failure) -> {
                    if (failure != null) state.failures.add(unwrap(failure));
                    for (DocumentSession document : state.pauseAttempted) {
                        if (document.isClosed()) state.closed.add(document);
                        else if (!state.keepPaused.contains(document)) document.resume();
                    }
                    return state.result();
                });
    }

    private static void checkReady(List<DocumentSnapshot> snapshots, State state) {
        if (snapshots.stream().anyMatch(DocumentSnapshot::needsAttention)) {
            throw new CompletionException(new IOException("Resolve the affected document's conflict or write error before changing its path."));
        }
        for (Map.Entry<DocumentSession, Path> entry : state.originalPaths.entrySet()) {
            if (entry.getKey().isClosed() || !entry.getValue().equals(entry.getKey().path())) {
                throw new CompletionException(new IOException("An affected document changed its lifecycle or path; the filesystem operation was cancelled."));
            }
        }
    }

    private CompletableFuture<Void> reconcile(State state) {
        if (!state.completed || state.kind == Kind.COPY) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> updates = new ArrayList<>();
        for (Map.Entry<DocumentSession, Path> entry : state.originalPaths.entrySet()) {
            DocumentSession document = entry.getKey();
            Path updatedPath = state.destination == null ? null : state.destination.resolve(state.source.relativize(entry.getValue()));
            CompletableFuture<?> update = state.kind == Kind.DELETE
                    ? document.discardRecoveryAndClose() : document.remapPath(updatedPath);
            updates.add(update.handle((ignored, failure) -> {
                if (document.isClosed()) state.closed.add(document);
                if (failure != null) {
                    if (!document.isClosed()) state.keepPaused.add(document);
                    synchronized (state.failures) {
                        state.failures.add(new IOException(state.kind == Kind.DELETE
                                ? "The file was deleted, but document cleanup failed. Its recovery is retained."
                                : "File moved to " + updatedPath + ", but its document could not follow the new path."
                                  + " Synchronization remains paused; keep recovery or save a copy before reopening the moved file.", unwrap(failure)));
                    }
                }
                return (Void) null;
            }));
        }
        return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new));
    }

    private static CompletableFuture<List<DocumentSnapshot>> snapshots(List<CompletableFuture<DocumentSnapshot>> requests) {
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> requests.stream().map(CompletableFuture::join).toList());
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static final class State {
        private final Kind kind;
        private final Path source;
        private Path destination;
        private final Map<DocumentSession, Path> originalPaths = new LinkedHashMap<>();
        private final Set<DocumentSession> pauseAttempted = ConcurrentHashMap.newKeySet();
        private final Set<DocumentSession> keepPaused = ConcurrentHashMap.newKeySet();
        private final Set<DocumentSession> closed = ConcurrentHashMap.newKeySet();
        private final List<Throwable> failures = new ArrayList<>();
        private boolean diskChanged;
        private boolean completed;

        private State(Kind kind, Path source, Path destination, List<DocumentSession> affected) {
            this.kind = kind; this.source = source; this.destination = destination;
            for (DocumentSession session : affected) originalPaths.put(session, session.path());
        }

        private Result result() {
            IOException failure = null;
            if (!failures.isEmpty()) {
                failure = new IOException(String.join("\n", failures.stream().map(error ->
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()).toList()), failures.getFirst());
                for (int index = 1; index < failures.size(); index++) failure.addSuppressed(failures.get(index));
            }
            return new Result(diskChanged, completed, destination, Set.copyOf(closed), Set.copyOf(keepPaused), failure);
        }
    }
}
