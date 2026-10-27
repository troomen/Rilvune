package dev.editor.document.application;

import dev.editor.document.domain.DraftRepository;
import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DocumentLifecycleTest {
    @TempDir Path directory;

    @Test void failedShutdownCanBeRetriedWithoutReportingUnsavedDocumentsAsClosed() throws Exception {
        FileDraftRepository storage = new FileDraftRepository(directory.resolve("drafts"));
        AtomicBoolean unavailable = new AtomicBoolean(true);
        DraftRepository repository = new DraftRepository() {
            @Override public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            @Override public void release(UUID id) throws IOException { storage.release(id); }
            @Override public void save(RecoveryDraft draft) throws IOException {
                if (unavailable.get()) throw new IOException("Recovery device unavailable");
                storage.save(draft);
            }
            @Override public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            @Override public void remove(UUID id) throws IOException { storage.remove(id); }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DocumentService service = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
        try {
            DocumentSession draft = service.createDraft(); draft.replaceText("Must survive a failed exit");
            assertThrows(ExecutionException.class, () -> service.close().get(5, TimeUnit.SECONDS));
            assertFalse(draft.isClosed()); assertFalse(service.sessions().isEmpty());
            assertThrows(ExecutionException.class, () -> service.close().get(5, TimeUnit.SECONDS));
            unavailable.set(false);
            service.close().get(5, TimeUnit.SECONDS);
            assertTrue(draft.isClosed()); assertTrue(service.sessions().isEmpty());
            assertEquals("Must survive a failed exit", storage.load().getFirst().text());
        } finally { unavailable.set(false); service.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); io.close(); }
    }

    @Test void recoveryIsReadAgainAfterAcquiringOwnership() throws Exception {
        FileDraftRepository storage = new FileDraftRepository(directory.resolve("drafts"));
        UUID id = UUID.randomUUID();
        RecoveryDraft stale = new RecoveryDraft(id, null, "old snapshot", 1, dev.editor.document.domain.TextFormat.utf8Lf(), null, null, dev.editor.document.domain.SyncState.DRAFT, null);
        RecoveryDraft latest = new RecoveryDraft(id, null, "last edit before owner closed", 2, stale.format(), null, null, stale.state(), null);
        storage.save(stale); storage.release(id);
        AtomicBoolean firstClaim = new AtomicBoolean(true);
        DraftRepository repository = new DraftRepository() {
            @Override public boolean claim(UUID requested) throws IOException {
                boolean acquired = storage.claim(requested);
                // The previous owner finished its last write between the initial scan and our claim.
                if (acquired && firstClaim.getAndSet(false)) storage.save(latest);
                return acquired;
            }
            @Override public void release(UUID requested) throws IOException { storage.release(requested); }
            @Override public void save(RecoveryDraft draft) throws IOException { storage.save(draft); }
            @Override public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            @Override public void remove(UUID requested) throws IOException { storage.remove(requested); }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DocumentService service = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
        try {
            DocumentSession recovered = service.restoreDrafts().get(5, TimeUnit.SECONDS).getFirst();
            assertEquals(latest.text(), recovered.text());
            recovered.flush().get(5, TimeUnit.SECONDS);
            assertEquals(latest.text(), storage.load().getFirst().text());
        } finally { service.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); io.close(); }
    }
}
