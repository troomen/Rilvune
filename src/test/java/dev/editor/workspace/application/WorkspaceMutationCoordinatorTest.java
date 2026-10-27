package dev.editor.workspace.application;

import dev.editor.document.application.DocumentService;
import dev.editor.document.application.DocumentSession;
import dev.editor.workspace.domain.PartialMoveException;
import dev.editor.document.domain.DiskSnapshot;
import dev.editor.document.domain.DocumentStore;
import dev.editor.document.domain.DraftRepository;
import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.domain.TextFormat;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import dev.editor.workspace.infrastructure.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMutationCoordinatorTest {
    @TempDir Path temporary;

    @Test void movingFolderFlushesLatestTextAndRebindsEveryOpenDescendant() throws Exception {
        try (Context context = context(new NioDocumentStore(1024 * 1024), repository())) {
            Path source = context.operations.createDirectory(Path.of("folder"));
            Path first = Files.writeString(source.resolve("first.txt"), "old");
            Path second = Files.writeString(source.resolve("second.txt"), "second");
            DocumentSession a = context.documents.open(first).get(5, TimeUnit.SECONDS);
            DocumentSession b = context.documents.open(second).get(5, TimeUnit.SECONDS);
            a.replaceText("latest edit");
            Path target = context.browse.resolve("renamed");
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.MOVE, source, target,
                    List.of(a, b), () -> {
                        assertTrue(a.isPaused()); assertTrue(b.isPaused());
                        context.operations.move(source, target);
                    }).get(5, TimeUnit.SECONDS);
            assertNull(result.failure());
            assertEquals(target.resolve("first.txt"), a.path());
            assertEquals(target.resolve("second.txt"), b.path());
            assertEquals("latest edit", Files.readString(a.path()));
            a.replaceText("after move"); a.flush().get(5, TimeUnit.SECONDS);
            assertEquals("after move", Files.readString(target.resolve("first.txt")));
            assertFalse(Files.exists(source));
        }
    }

    @Test void copyUsesFlushedTextAndRetainsOriginalDocumentPath() throws Exception {
        try (Context context = context(new NioDocumentStore(1024 * 1024), repository())) {
            Path source = Files.writeString(context.browse.resolve("source.txt"), "old");
            DocumentSession document = context.documents.open(source).get(5, TimeUnit.SECONDS);
            document.replaceText("new text");
            Path target = context.browse.resolve("copy.txt");
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.COPY, source, target,
                    List.of(document), () -> context.operations.copy(source, target)).get(5, TimeUnit.SECONDS);
            assertNull(result.failure());
            assertEquals(source, document.path());
            assertEquals("new text", Files.readString(target));
            assertFalse(document.isPaused());
        }
    }

    @Test void rebindFailureAfterMoveKeepsRecoveryPausedAndNeverRecreatesOldPath() throws Exception {
        Path destination = temporary.resolve("projects/renamed.txt");
        DocumentStore delegate = new NioDocumentStore(1024 * 1024);
        DocumentStore failingRebind = new DocumentStore() {
            @Override public Path canonicalPath(Path path) throws IOException {
                if (path.equals(destination)) throw new IOException("Simulated failed rebind");
                return delegate.canonicalPath(path);
            }
            @Override public DiskSnapshot read(Path path) throws IOException { return delegate.read(path); }
            @Override public DiskSnapshot write(Path path, String text, TextFormat format, DiskSnapshot expected) throws IOException {
                return delegate.write(path, text, format, expected);
            }
        };
        DraftRepository repository = repository();
        try (Context context = context(failingRebind, repository)) {
            Path source = Files.writeString(context.browse.resolve("source.txt"), "preserved");
            DocumentSession document = context.documents.open(source).get(5, TimeUnit.SECONDS);
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.MOVE, source, destination,
                    List.of(document), () -> context.operations.move(source, destination)).get(5, TimeUnit.SECONDS);
            assertNotNull(result.failure());
            assertTrue(result.pausedDocuments().contains(document));
            assertTrue(document.isPaused());
            document.replaceText("retained in recovery"); document.flush().get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(source));
            assertEquals("preserved", Files.readString(destination));
            assertEquals("retained in recovery", repository.load().getFirst().text());
        }
    }

    @Test void completeDestinationFromPartialMoveBecomesDocumentPath() throws Exception {
        try (Context context = context(new NioDocumentStore(1024 * 1024), repository())) {
            Path source = Files.writeString(context.browse.resolve("source.txt"), "keep");
            Path target = context.browse.resolve("target.txt");
            DocumentSession document = context.documents.open(source).get(5, TimeUnit.SECONDS);
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.MOVE, source, target,
                    List.of(document), () -> {
                        Files.copy(source, target);
                        throw new PartialMoveException(source, target, new IOException("Simulated source cleanup failure"));
                    }).get(5, TimeUnit.SECONDS);
            assertNotNull(result.failure());
            assertEquals(target, document.path());
            assertFalse(document.isPaused());
            assertEquals("keep", Files.readString(source));
            assertEquals("keep", Files.readString(target));
        }
    }

    @Test void deleteClosesDocumentWithoutAllowingAutoSaveToRecreateIt() throws Exception {
        DraftRepository repository = repository();
        try (Context context = context(new NioDocumentStore(1024 * 1024), repository)) {
            Path source = Files.writeString(context.browse.resolve("source.txt"), "delete me");
            DocumentSession document = context.documents.open(source).get(5, TimeUnit.SECONDS);
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.DELETE, source, null,
                    List.of(document), () -> context.operations.delete(source)).get(5, TimeUnit.SECONDS);
            assertNull(result.failure());
            assertTrue(result.closedDocuments().contains(document));
            assertTrue(document.isClosed());
            assertFalse(Files.exists(source));
            assertTrue(repository.load().isEmpty());
            assertTrue(context.documents.sessions().isEmpty());
        }
    }

    @Test void failedRecoveryCleanupStillReportsTheSessionAsClosed() throws Exception {
        DraftRepository delegate = repository();
        AtomicBoolean deletionCompleted = new AtomicBoolean();
        DraftRepository failingRemoval = new DraftRepository() {
            @Override public void save(RecoveryDraft draft) throws IOException { delegate.save(draft); }
            @Override public List<RecoveryDraft> load() throws IOException { return delegate.load(); }
            @Override public void remove(UUID id) throws IOException { if (deletionCompleted.get()) throw new IOException("Cannot remove recovery record"); delegate.remove(id); }
        };
        try (Context context = context(new NioDocumentStore(1024 * 1024), failingRemoval)) {
            Path source = Files.writeString(context.browse.resolve("source.txt"), "deleted with retained recovery");
            DocumentSession document = context.documents.open(source).get(5, TimeUnit.SECONDS);
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.DELETE, source, null,
                    List.of(document), () -> { context.operations.delete(source); deletionCompleted.set(true); }).get(5, TimeUnit.SECONDS);
            assertNotNull(result.failure());
            assertTrue(result.closedDocuments().contains(document));
            assertTrue(document.isClosed());
            assertEquals(1, delegate.load().size());
        }
    }

    @Test void pauseRecoveryFailureCancelsMutationBeforeTouchingDisk() throws Exception {
        DraftRepository delegate = repository();
        DraftRepository failingPause = new DraftRepository() {
            @Override public void save(RecoveryDraft draft) throws IOException {
                if (draft.state() == dev.editor.document.domain.SyncState.PAUSED) throw new IOException("Cannot persist paused recovery");
                delegate.save(draft);
            }
            @Override public List<RecoveryDraft> load() throws IOException { return delegate.load(); }
            @Override public void remove(UUID id) throws IOException { delegate.remove(id); }
        };
        try (Context context = context(new NioDocumentStore(1024 * 1024), failingPause)) {
            Path source = Files.writeString(context.browse.resolve("source.txt"), "keep");
            DocumentSession document = context.documents.open(source).get(5, TimeUnit.SECONDS);
            AtomicBoolean changed = new AtomicBoolean();
            var result = context.coordinator.execute(WorkspaceMutationCoordinator.Kind.MOVE, source,
                    context.browse.resolve("target.txt"), List.of(document), () -> changed.set(true)).get(5, TimeUnit.SECONDS);
            assertNotNull(result.failure());
            assertFalse(changed.get());
            assertEquals("keep", Files.readString(source));
        }
    }

    private DraftRepository repository() { return new FileDraftRepository(temporary.resolve("drafts")); }
    private Context context(DocumentStore store, DraftRepository repository) throws IOException { return new Context(store, repository); }

    private final class Context implements AutoCloseable {
        private final Path browse = Files.createDirectories(temporary.resolve("projects"));
        private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final DocumentService documents;
        private final WorkspaceOperations operations = new WorkspaceOperations(browse, temporary.resolve("profile"));
        private final WorkspaceMutationCoordinator coordinator = new WorkspaceMutationCoordinator(io);
        private Context(DocumentStore store, DraftRepository repository) throws IOException {
            documents = new DocumentService(store, repository, io, scheduler, Runnable::run, Duration.ofDays(1));
        }
        @Override public void close() throws Exception {
            documents.close().get(5, TimeUnit.SECONDS);
            scheduler.shutdownNow(); io.close();
        }
    }
}
