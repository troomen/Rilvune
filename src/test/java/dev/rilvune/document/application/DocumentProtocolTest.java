package dev.rilvune.document.application;

import dev.rilvune.document.domain.*;
import dev.rilvune.document.infrastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DocumentProtocolTest {
    @TempDir Path directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean rejectNotifications = new AtomicBoolean();
    private DocumentService documents;
    private DocumentSession document;
    private Path path;

    @BeforeEach void setup() throws Exception {
        documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")), io, scheduler,
                task -> { if (rejectNotifications.get()) throw new RejectedExecutionException("Notifications stopped"); task.run(); }, Duration.ofDays(1));
        path = Files.writeString(directory.resolve("file.txt"), "base"); document = await(documents.open(path));
    }
    @AfterEach void close() throws Exception { rejectNotifications.set(false); await(documents.close()); scheduler.shutdownNow(); io.close(); }
    private <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }

    @Test void guardedChangesExplainRejectionAndPublishTheAcceptedRevision() throws Exception {
        long base = document.revision();
        var changed = document.changeIfCurrent(base, path, "new text");
        assertEquals(DocumentChange.Effect.APPLIED, changed.effect()); assertEquals(base + 1, changed.snapshot().revision());
        assertEquals(DocumentChange.Rejection.STALE_DOCUMENT, document.changeIfCurrent(base, path, "stale").rejection());
        assertEquals(DocumentChange.Rejection.STALE_DOCUMENT, document.changeIfCurrent(base + 1, path.resolveSibling("other"), "stale").rejection());
        await(document.flush());
        assertEquals(DocumentChange.Effect.UNCHANGED, document.changeIfCurrent(base + 1, path, "new text").effect());
        await(document.pause());
        assertEquals(DocumentChange.Rejection.PAUSED, document.changeIfCurrent(base + 1, path, "blocked").rejection());
        document.resume(); await(document.flush()); await(document.close());
        assertEquals(DocumentChange.Rejection.CLOSED, document.changeIfCurrent(base + 1, path, "blocked").rejection());
        assertEquals("new text", Files.readString(path));
    }

    @Test void failingObserversDoNotPreventOtherObserversOrDurableClose() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        document.addListener(snapshot -> { throw new IllegalStateException("Broken document observer"); });
        document.addListener(snapshot -> delivered.incrementAndGet());
        documents.addListener(snapshot -> { throw new IllegalStateException("Broken service observer"); });
        AtomicInteger serviceDelivered = new AtomicInteger(); documents.addListener(snapshot -> serviceDelivered.incrementAndGet());
        var changed = document.changeIfCurrent(document.revision(), path, "saved despite observer");
        assertTrue(changed.accepted()); assertNotNull(changed.deliveryFailure());
        await(document.close()); assertTrue(delivered.get() > 0); assertTrue(serviceDelivered.get() > 0);
        assertEquals("saved despite observer", Files.readString(path));
    }

    @Test void notificationDispatchRejectionCannotStrandFlushOrExternalReconciliation() throws Exception {
        rejectNotifications.set(true);
        document.replaceText("local"); await(document.flush()); assertEquals("local", Files.readString(path));
        Files.writeString(path, "external");
        var failed = await(document.flush()); assertEquals(SyncState.ERROR, failed.state());
        assertEquals("local", document.text());
        rejectNotifications.set(false); await(document.flush()); assertEquals("external", document.text());
    }

    @Test void initialIoRejectionAllowsBothFlushAndCloseToRetry() throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        var session = new DocumentSession(new RecoveryDraft(UUID.randomUUID(), null, "retained", 0, TextFormat.utf8Lf(),
                null, null, SyncState.DRAFT, null), new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("rejected")),
                task -> { if (reject.get()) throw new RejectedExecutionException("IO stopped"); io.execute(task); }, Runnable::run, ignored -> { }, ignored -> { });
        assertThrows(ExecutionException.class, () -> await(session.flush()));
        assertThrows(ExecutionException.class, () -> await(session.close())); assertFalse(session.isClosing());
        reject.set(false); await(session.flush()); await(session.close()); assertTrue(session.isClosed());
        assertEquals("retained", new FileDraftRepository(directory.resolve("rejected")).load().getFirst().text());
    }

    @Test void restoredPauseRetainsTextAndRequiresReviewWithoutImplicitOverwrite() throws Exception {
        Path pausedPath = Files.writeString(directory.resolve("paused.txt"), "base");
        var store = new NioDocumentStore(10000); var disk = store.read(pausedPath);
        var paused = new DocumentSession(new RecoveryDraft(UUID.randomUUID(), pausedPath, "local", 1, disk.format(), disk, disk,
                SyncState.PAUSED, null), store, new FileDraftRepository(directory.resolve("paused-drafts")), io, Runnable::run, ignored -> { }, ignored -> { });
        try {
            assertTrue(paused.isPaused()); assertTrue(paused.snapshot().recoveredPause()); assertTrue(paused.snapshot().needsAttention());
            paused.resume(); await(paused.flush()); assertTrue(paused.isPaused()); assertEquals("base", Files.readString(pausedPath));
            Files.writeString(pausedPath, "outside");
            var reviewed = await(paused.reviewAndResume());
            assertFalse(paused.isPaused()); assertFalse(reviewed.recoveredPause()); assertEquals(SyncState.CONFLICT, reviewed.state());
            assertEquals("local", paused.text()); assertEquals("outside", Files.readString(pausedPath));
            assertEquals(DocumentChange.Rejection.NEEDS_RESOLUTION, paused.changeIfCurrent(paused.revision(), pausedPath, "blocked").rejection());
        } finally { await(paused.close()); }
    }

    @Test void rejectionAfterOneWorkerCompletesFailsQueuedActionsAndCloseWithoutHanging() throws Exception {
        var workers = new java.util.ArrayDeque<Runnable>(); var reject = new AtomicBoolean();
        var session = new DocumentSession(new RecoveryDraft(UUID.randomUUID(), null, "retained", 0, TextFormat.utf8Lf(),
                null, null, SyncState.DRAFT, null), new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("queued")),
                task -> { if (reject.get()) throw new RejectedExecutionException("Next worker refused"); workers.add(task); },
                Runnable::run, ignored -> { }, ignored -> { });
        var flush = session.flush(); var copy = session.saveCopy(directory.resolve("not-created.txt")); var pause = session.pause();
        var closing = session.close(); reject.set(true); workers.remove().run();
        await(flush); assertThrows(ExecutionException.class, () -> await(copy));
        assertThrows(ExecutionException.class, () -> await(pause)); assertThrows(ExecutionException.class, () -> await(closing));
        assertFalse(session.isClosing()); assertFalse(Files.exists(directory.resolve("not-created.txt")));
        reject.set(false); var retry = session.close();
        while (!retry.isDone()) { var task = workers.poll(); assertNotNull(task); task.run(); }
        await(retry); assertTrue(session.isClosed());
    }

    @Test void cancelledFlushObserverDoesNotCancelPersistenceOrAnotherWaiter() throws Exception {
        document.replaceText("durable"); var observer = document.flush(); observer.cancel(false);
        await(document.flush()); assertEquals("durable", Files.readString(path)); await(document.close());
    }
}
