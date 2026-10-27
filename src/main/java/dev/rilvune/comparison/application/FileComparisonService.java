package dev.rilvune.comparison.application;

import dev.rilvune.document.application.DocumentHandle;
import dev.rilvune.document.application.DocumentService;
import dev.rilvune.session.domain.SessionState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;

/** Resolves and acquires sources without involving Swing or materializing comparison files. */
public final class FileComparisonService {
    private final DocumentService documents;
    private final Path browseRoot;
    private final ExecutorService workers;
    private final Set<DocumentHandle> cleanup = ConcurrentHashMap.newKeySet();

    public FileComparisonService(DocumentService documents, Path browseRoot, ExecutorService workers) {
        this.documents = documents; this.browseRoot = browseRoot; this.workers = workers;
    }
    public CompletableFuture<ComparisonPair> open(SessionState.Source left, SessionState.Source right) {
        return validate(left, right).thenCompose(ignored -> acquire(left)).thenCompose(a ->
                acquire(right).handle((b, failure) -> {
                    if (failure != null) { releaseLater(a); throw new CompletionException(unwrap(failure)); }
                    if (a.session() == b.session()) {
                        releaseLater(a); releaseLater(b);
                        throw new CompletionException(new IOException("Choose two different files"));
                    }
                    return new ComparisonPair(a, b);
                }));
    }
    private CompletableFuture<Void> validate(SessionState.Source left, SessionState.Source right) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path a = checked(left), b = checked(right);
                if ((a != null && b != null && Files.isSameFile(a, b))
                        || (a == null && b == null && left.id().equals(right.id()))) throw new IOException("Choose two different files");
            } catch (IOException failure) { throw new CompletionException(failure); }
        }, workers);
    }
    private Path checked(SessionState.Source source) throws IOException {
        if (source.path() == null) return null;
        Path path = source.path().toRealPath();
        if (!path.startsWith(browseRoot) || !Files.isRegularFile(path)) throw new IOException("Choose a text file inside BROWSE_ROOT");
        return path;
    }
    private CompletableFuture<DocumentHandle> acquire(SessionState.Source source) {
        var existing = documents.sessions().stream().filter(session -> !session.isClosed() && !session.isClosing()
                && (session.id().toString().equals(source.id()) || source.path() != null && source.path().equals(session.path()))).findFirst();
        if (existing.isPresent()) {
            try { return CompletableFuture.completedFuture(documents.retain(existing.orElseThrow())); }
            catch (IllegalStateException failure) { return CompletableFuture.failedFuture(failure); }
        }
        if (source.path() == null) return CompletableFuture.failedFuture(new IOException("The source draft is no longer available"));
        return CompletableFuture.supplyAsync(() -> {
            try { return checked(source); } catch (IOException failure) { throw new CompletionException(failure); }
        }, workers).thenCompose(documents::acquire);
    }
    public CompletableFuture<Void> releaseLater(DocumentHandle handle) {
        cleanup.add(handle);
        return handle.release().thenRun(() -> cleanup.remove(handle));
    }
    public CompletableFuture<Void> discard(ComparisonPair pair) {
        return CompletableFuture.allOf(releaseLater(pair.left()), releaseLater(pair.right()));
    }
    /** Failed cleanup stays owned and retryable; project transitions must finish it or report failure. */
    public CompletableFuture<Void> cleanup() {
        return CompletableFuture.allOf(cleanup.stream().map(handle -> handle.release().thenRun(() -> cleanup.remove(handle))).toArray(CompletableFuture[]::new));
    }
    private Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
