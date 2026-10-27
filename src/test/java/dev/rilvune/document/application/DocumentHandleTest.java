package dev.rilvune.document.application;

import dev.rilvune.document.domain.DraftRepository;
import dev.rilvune.document.domain.RecoveryDraft;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DocumentHandleTest {
    @TempDir Path directory;

    @Test void consumersReleaseIndependentlyAndOnlyTheLastReleaseClosesTheSession() throws Exception {
        try (var io = Executors.newSingleThreadExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            DocumentService service = service(new FileDraftRepository(directory.resolve("drafts")), io, scheduler);
            try {
                DocumentSession session = service.open(Files.writeString(directory.resolve("file.txt"), "initial")).get(5, TimeUnit.SECONDS);
                DocumentHandle first = service.retain(session);
                DocumentHandle second = service.retain(session);
                first.release().get(5, TimeUnit.SECONDS);
                first.release().get(5, TimeUnit.SECONDS);
                assertTrue(first.isReleased());
                assertFalse(session.isClosed());
                session.replaceText("still editable");
                second.release().get(5, TimeUnit.SECONDS);
                assertTrue(session.isClosed());
                assertEquals("still editable", Files.readString(directory.resolve("file.txt")));
                assertTrue(service.sessions().isEmpty());
                assertThrows(IllegalStateException.class, () -> service.retain(session));
            } finally { service.close().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void failedFinalReleaseCanBeRetriedWithoutLosingConsumerOwnership() throws Exception {
        FileDraftRepository storage = new FileDraftRepository(directory.resolve("drafts"));
        AtomicBoolean reject = new AtomicBoolean(true);
        DraftRepository repository = new DraftRepository() {
            @Override public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            @Override public void save(RecoveryDraft draft) throws IOException { storage.save(draft); }
            @Override public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            @Override public void remove(UUID id) throws IOException { storage.remove(id); }
            @Override public void release(UUID id) throws IOException {
                if (reject.get()) throw new IOException("Simulated final release failure");
                storage.release(id);
            }
        };
        try (var io = Executors.newSingleThreadExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            DocumentService service = service(repository, io, scheduler);
            try {
                DocumentSession session = service.createDraft();
                DocumentHandle original = service.retain(session);
                assertThrows(ExecutionException.class, () -> original.release().get(5, TimeUnit.SECONDS));
                assertFalse(original.isReleased());
                assertFalse(session.isClosed());
                DocumentHandle another = service.retain(session);
                original.release().get(5, TimeUnit.SECONDS);
                assertTrue(original.isReleased());
                assertFalse(session.isClosed());
                reject.set(false);
                another.release().get(5, TimeUnit.SECONDS);
                assertTrue(session.isClosed());
            } finally { reject.set(false); service.close().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void projectShutdownClosesUniqueSessionsEvenWithOutstandingHandles() throws Exception {
        try (var io = Executors.newSingleThreadExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            DocumentService service = service(new FileDraftRepository(directory.resolve("drafts")), io, scheduler);
            DocumentSession session = service.createDraft();
            DocumentHandle first = service.retain(session);
            DocumentHandle second = service.retain(session);
            session.replaceText("preserved recovery");
            service.close().get(5, TimeUnit.SECONDS);
            assertTrue(session.isClosed());
            first.release().get(5, TimeUnit.SECONDS);
            second.release().get(5, TimeUnit.SECONDS);
            assertTrue(first.isReleased()); assertTrue(second.isReleased());
            assertEquals("preserved recovery", new FileDraftRepository(directory.resolve("drafts")).load().getFirst().text());
        }
    }

    @Test void lateCloseCompletionCannotClearOwnershipOfARestoredSessionWithTheSameIdentity() throws Exception {
        FileDraftRepository storage = new FileDraftRepository(directory.resolve("drafts"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean firstRelease = new AtomicBoolean(true);
        DraftRepository repository = new DraftRepository() {
            @Override public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            @Override public void save(RecoveryDraft draft) throws IOException { storage.save(draft); }
            @Override public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            @Override public void remove(UUID id) throws IOException { storage.remove(id); }
            @Override public void release(UUID id) throws IOException {
                if (firstRelease.getAndSet(false)) {
                    entered.countDown();
                    try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("Close gate timed out"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                }
                storage.release(id);
            }
        };
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            DocumentService service = service(repository, io, scheduler);
            try {
                DocumentSession previous = service.createDraft(); previous.replaceText("recovered content");
                var oldRelease = service.retain(previous).release();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var owners = new java.util.concurrent.atomic.AtomicReference<List<DocumentHandle>>();
                var reopened = previous.close().thenRun(() -> {
                    DocumentSession restored = service.restoreDrafts().join().getFirst();
                    assertEquals(previous.id(), restored.id());
                    owners.set(List.of(service.retain(restored), service.retain(restored)));
                });
                proceed.countDown();
                reopened.get(5, TimeUnit.SECONDS); oldRelease.get(5, TimeUnit.SECONDS);
                DocumentHandle first = owners.get().getFirst(); DocumentHandle last = owners.get().getLast();
                first.release().get(5, TimeUnit.SECONDS);
                assertFalse(last.session().isClosed());
                last.release().get(5, TimeUnit.SECONDS);
                assertTrue(last.session().isClosed(), "The restored session must retain its own consumer count");
            } finally { proceed.countDown(); service.close().get(5, TimeUnit.SECONDS); }
        }
    }

    private DocumentService service(DraftRepository repository, java.util.concurrent.ExecutorService io,
                                    java.util.concurrent.ScheduledExecutorService scheduler) {
        return new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
    }
}
