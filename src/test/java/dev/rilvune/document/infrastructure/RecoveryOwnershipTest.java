package dev.rilvune.document.infrastructure;

import dev.rilvune.document.application.DocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryOwnershipTest {
    @TempDir Path directory;
    @Test void activeDraftCannotBeRestoredOrOverwrittenByAnotherInstanceUntilReleased() throws Exception {
        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor(); ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        FileDraftRepository firstStore = new FileDraftRepository(directory.resolve("drafts")); FileDraftRepository secondStore = new FileDraftRepository(directory.resolve("drafts"));
        DocumentService first = new DocumentService(new NioDocumentStore(10000), firstStore, io, scheduler, Runnable::run, Duration.ofDays(1));
        DocumentService second = new DocumentService(new NioDocumentStore(10000), secondStore, io, scheduler, Runnable::run, Duration.ofDays(1));
        try {
            var draft = first.createDraft(); draft.replaceText("private unsaved draft"); draft.flush().get(5, TimeUnit.SECONDS);
            assertTrue(second.restoreDrafts().get(5, TimeUnit.SECONDS).isEmpty());
            assertFalse(secondStore.claim(draft.id()));
            draft.close().get(5, TimeUnit.SECONDS);
            var recovered = second.restoreDrafts().get(5, TimeUnit.SECONDS); assertEquals(1, recovered.size()); assertEquals("private unsaved draft", recovered.getFirst().text());
            recovered.getFirst().replaceText("second instance update"); recovered.getFirst().flush().get(5, TimeUnit.SECONDS);
            assertEquals("second instance update", firstStore.load().getFirst().text());
            assertEquals("second instance update", firstStore.find(draft.id()).orElseThrow().text());
            assertTrue(firstStore.find(java.util.UUID.randomUUID()).isEmpty());
        } finally { first.close().get(5, TimeUnit.SECONDS); second.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); io.close(); }
    }
}
