package dev.editor.workspace.application;

import dev.editor.document.application.*;
import dev.editor.document.domain.*;
import dev.editor.document.infrastructure.*;
import dev.editor.workspace.domain.*;
import dev.editor.workspace.infrastructure.NioWorkspaceFiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ProjectDocumentsTest {
    @TempDir Path directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean failRelease = new AtomicBoolean(), failWrites = new AtomicBoolean(), failRecovery = new AtomicBoolean();
    private DocumentService documents;
    private NioWorkspaceFiles files;
    private ProjectDocuments gateway;
    private ProjectContext context;
    private Path root, path;

    @BeforeEach void setup() throws Exception {
        root = Files.createDirectory(directory.resolve("project")); path = Files.writeString(root.resolve("file.txt"), "base");
        var disk = new NioDocumentStore(10000);
        var store = new DocumentStore() {
            public Path canonicalPath(Path file) throws IOException { return disk.canonicalPath(file); }
            public DiskSnapshot read(Path file) throws IOException { return disk.read(file); }
            public DiskSnapshot write(Path file, String text, TextFormat format, DiskSnapshot expected) throws IOException {
                if (failWrites.get()) throw new IOException("Source unavailable");
                return disk.write(file, text, format, expected);
            }
        };
        var storage = new FileDraftRepository(directory.resolve("drafts"));
        var recovery = new DraftRepository() {
            public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            public void save(RecoveryDraft draft) throws IOException {
                if (failRecovery.get()) throw new IOException("Recovery unavailable"); storage.save(draft);
            }
            public void remove(UUID id) throws IOException {
                if (failRecovery.get()) throw new IOException("Recovery unavailable"); storage.remove(id);
            }
            public void release(UUID id) throws IOException {
                if (failRelease.get()) throw new IOException("Release unavailable"); storage.release(id);
            }
        };
        documents = new DocumentService(store, recovery, io, scheduler, Runnable::run, Duration.ofDays(1));
        files = new NioWorkspaceFiles(10000); context = ProjectContext.activate(files.openWorkspace(root));
        gateway = new ProjectDocuments(documents, files, Runnable::run, io); gateway.resume(context);
    }
    @AfterEach void close() throws Exception {
        failRelease.set(false); failWrites.set(false); failRecovery.set(false);
        await(gateway.quiesce()); await(documents.close()); scheduler.shutdownNow(); io.close();
    }
    private <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    private ProjectDocuments.ScopedDocument open() throws Exception { return await(gateway.open(context, Path.of("file.txt"))); }
    private ProjectDocuments.Rejection reason(CompletableFuture<?> future) {
        var error = assertThrows(ExecutionException.class, () -> await(future));
        assertInstanceOf(ProjectDocuments.Failure.class, error.getCause()); return ((ProjectDocuments.Failure) error.getCause()).reason();
    }

    @Test void readsLiveTextAndRetainsTheDocumentAfterItsViewConsumerCloses() throws Exception {
        var tab = await(documents.acquire(path)); tab.session().replaceText("unsaved current text");
        var consumer = open(); var snapshot = consumer.snapshot();
        assertEquals("unsaved current text", snapshot.document().text());
        await(tab.release()); assertFalse(tab.session().isClosed());
        var result = await(consumer.apply(snapshot.version(), "programmatic text"));
        assertTrue(result.accepted()); assertEquals(SyncState.SYNCHRONIZED, result.sourceState()); assertNull(result.persistenceFailure());
        assertEquals("programmatic text", Files.readString(path));
        await(consumer.release()); assertTrue(documents.sessions().isEmpty());
    }

    @Test void userEditOrWrongConsumerVersionRejectsWithoutChangingText() throws Exception {
        var first = open(); var second = open(); var version = first.snapshot().version();
        assertEquals(ProjectDocuments.Rejection.STALE_DOCUMENT, await(second.apply(version, "wrong owner")).rejection());
        documents.sessions().getFirst().replaceText("newer user text");
        assertEquals(ProjectDocuments.Rejection.STALE_DOCUMENT, await(first.apply(version, "stale")).rejection());
        assertEquals("newer user text", first.snapshot().document().text());
    }

    @Test void independentConsumersAcceptOneRevisionAndRejectTheStaleSecond() throws Exception {
        var first = open(); var second = open(); var a = first.snapshot().version(); var b = second.snapshot().version();
        assertTrue(await(first.apply(a, "first change")).accepted());
        assertEquals(ProjectDocuments.Rejection.STALE_DOCUMENT, await(second.apply(b, "second change")).rejection());
        assertEquals("first change", Files.readString(path));
    }

    @Test void lateAcquisitionIsReleasedAndOldActivationNeverRevives() throws Exception {
        var work = new LinkedBlockingQueue<Runnable>();
        await(gateway.quiesce()); gateway = new ProjectDocuments(documents, files, Runnable::run, work::add); gateway.resume(context);
        var opening = gateway.open(context, Path.of("file.txt")); var oldContext = context;
        var drain = gateway.quiesce(); assertFalse(drain.isDone());
        Runnable acquisition = work.poll(5, TimeUnit.SECONDS); assertNotNull(acquisition); acquisition.run();
        await(drain); assertEquals(ProjectDocuments.Rejection.STALE_PROJECT, reason(opening));
        assertTrue(documents.sessions().isEmpty());
        context = ProjectContext.activate(new Workspace(root)); gateway.resume(context);
        assertEquals(ProjectDocuments.Rejection.STALE_PROJECT, reason(gateway.open(oldContext, Path.of("file.txt"))));
    }

    @Test void quiescenceWaitsForAnAlreadyAdmittedEditButRejectsLaterProposals() throws Exception {
        var work = new LinkedBlockingQueue<Runnable>();
        await(gateway.quiesce()); gateway = new ProjectDocuments(documents, files, Runnable::run, work::add); gateway.resume(context);
        var opening = gateway.open(context, Path.of("file.txt")); work.poll(5, TimeUnit.SECONDS).run();
        var consumer = await(opening); var version = consumer.snapshot().version();
        var change = consumer.apply(version, "admitted before stop"); var drain = gateway.quiesce();
        assertFalse(drain.isDone());
        assertEquals(ProjectDocuments.Rejection.STALE_PROJECT, await(consumer.apply(version, "late")).rejection());
        work.poll(5, TimeUnit.SECONDS).run();
        assertTrue(await(change).accepted()); await(drain);
        assertEquals("admitted before stop", Files.readString(path)); assertTrue(documents.sessions().isEmpty());
    }

    @Test void cancelledObserversDoNotLoseAcquisitionOrAcceptedChanges() throws Exception {
        var consumer = open(); var version = consumer.snapshot().version();
        var change = consumer.apply(version, "still durable"); change.cancel(false);
        await(gateway.quiesce()); assertEquals("still durable", Files.readString(path)); assertTrue(documents.sessions().isEmpty());
    }

    @Test void releaseFailureKeepsBudgetAndCanBeRetried() throws Exception {
        await(gateway.quiesce()); gateway = new ProjectDocuments(documents, files, Runnable::run, io, 1, 100); gateway.resume(context);
        var consumer = open(); failRelease.set(true);
        assertThrows(ExecutionException.class, () -> await(consumer.release()));
        assertEquals(ProjectDocuments.Rejection.LIMIT_EXCEEDED, reason(gateway.open(context, Path.of("file.txt"))));
        failRelease.set(false); await(consumer.release());
        var next = open(); assertNotNull(next.snapshot());
    }

    @Test void snapshotsAndPreparedResultsShareABoundedBudgetWithoutTruncation() throws Exception {
        await(gateway.quiesce()); gateway = new ProjectDocuments(documents, files, Runnable::run, io, 2, 12); gateway.resume(context);
        var consumer = open(); var snapshot = consumer.snapshot();
        assertEquals(ProjectDocuments.Rejection.LIMIT_EXCEEDED, await(consumer.apply(snapshot.version(), "x".repeat(9))).rejection());
        assertEquals("base", Files.readString(path));
        assertTrue(await(consumer.apply(snapshot.version(), "12345678")).accepted());
        assertEquals("12345678", consumer.snapshot().document().text());
        documents.sessions().getFirst().replaceText("x".repeat(13));
        var failed = assertThrows(ProjectDocuments.Failure.class, consumer::snapshot);
        assertEquals(ProjectDocuments.Rejection.LIMIT_EXCEEDED, failed.reason());
    }

    @Test void traversalAndSymlinkOutsideTheProjectAreTypedFailures() throws Exception {
        Files.writeString(directory.resolve("outside.txt"), "outside");
        assertEquals(ProjectDocuments.Rejection.OUTSIDE_PROJECT, reason(gateway.open(context, Path.of("../outside.txt"))));
        Files.createSymbolicLink(root.resolve("link.txt"), directory.resolve("outside.txt"));
        assertEquals(ProjectDocuments.Rejection.OUTSIDE_PROJECT, reason(gateway.open(context, Path.of("link.txt"))));
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void sourceOrRecoveryFailureDoesNotPretendAnAcceptedEditWasRolledBack() throws Exception {
        var consumer = open(); var version = consumer.snapshot().version(); failWrites.set(true);
        var sourceFailure = await(consumer.apply(version, "live text"));
        assertTrue(sourceFailure.accepted()); assertEquals(SyncState.ERROR, sourceFailure.sourceState());
        assertEquals("live text", consumer.snapshot().document().text()); assertEquals("base", Files.readString(path));
        failWrites.set(false); await(documents.flushAll()); version = consumer.snapshot().version(); failRecovery.set(true);
        var recoveryFailure = await(consumer.apply(version, "source saved"));
        assertTrue(recoveryFailure.accepted()); assertNotNull(recoveryFailure.persistenceFailure()); assertNotNull(recoveryFailure.recoveryError());
        assertEquals(SyncState.SYNCHRONIZED, recoveryFailure.sourceState()); assertEquals("source saved", Files.readString(path));
        failRecovery.set(false); await(documents.flushAll());
    }

    @Test void rejectedControlContinuationCompletesAcceptedWorkAndAllowsCleanup() throws Exception {
        var work = new LinkedBlockingQueue<Runnable>(); var reject = new AtomicBoolean();
        await(gateway.quiesce());
        gateway = new ProjectDocuments(documents, files, task -> {
            if (reject.get()) throw new RejectedExecutionException("Control unavailable"); task.run();
        }, work::add);
        gateway.resume(context);
        var opening = gateway.open(context, Path.of("file.txt")); work.poll(5, TimeUnit.SECONDS).run();
        var consumer = await(opening); var applying = consumer.apply(consumer.snapshot().version(), "not applied");
        var drain = gateway.quiesce(); reject.set(true); work.poll(5, TimeUnit.SECONDS).run();
        var result = await(applying); assertFalse(result.accepted()); assertNotNull(result.persistenceFailure());
        await(drain); assertTrue(documents.sessions().isEmpty()); assertEquals("base", Files.readString(path));
    }

    @Test void cancelledAcquisitionObserverStillReleasesTheUnclaimedConsumer() throws Exception {
        var work = new LinkedBlockingQueue<Runnable>();
        await(gateway.quiesce()); gateway = new ProjectDocuments(documents, files, Runnable::run, work::add); gateway.resume(context);
        var opening = gateway.open(context, Path.of("file.txt")); opening.cancel(false);
        work.poll(5, TimeUnit.SECONDS).run();
        // Quiescence also proves the cancelled arrival cannot publish an untracked handle.
        await(gateway.quiesce()); assertTrue(documents.sessions().isEmpty());
    }

    @Test void concurrentAcquisitionsCannotPublishSnapshotsBeyondTheirSharedBudget() throws Exception {
        Files.writeString(root.resolve("second.txt"), "12345");
        await(gateway.quiesce()); gateway = new ProjectDocuments(documents, files, Runnable::run, io, 2, 8); gateway.resume(context);
        var first = gateway.open(context, Path.of("file.txt")); var second = gateway.open(context, Path.of("second.txt"));
        var all = CompletableFuture.allOf(first.handle((value, error) -> null), second.handle((value, error) -> null)); await(all);
        assertNotEquals(first.isCompletedExceptionally(), second.isCompletedExceptionally());
        var failed = first.isCompletedExceptionally() ? first : second;
        assertEquals(ProjectDocuments.Rejection.LIMIT_EXCEEDED, reason(failed));
        await(gateway.quiesce()); assertTrue(documents.sessions().isEmpty());
    }

    @Test void pausedAndReleasedConsumersCannotAcceptAnOrdinaryEdit() throws Exception {
        var consumer = open(); var version = consumer.snapshot().version(); var session = documents.sessions().getFirst();
        await(session.pause());
        assertEquals(ProjectDocuments.Rejection.PAUSED, await(consumer.apply(version, "blocked")).rejection());
        session.resume(); await(consumer.release());
        assertEquals(ProjectDocuments.Rejection.CLOSED, await(consumer.apply(version, "blocked")).rejection());
        assertThrows(ProjectDocuments.Failure.class, consumer::snapshot);
    }
}
