package dev.rilvune.document.application;

import dev.rilvune.document.domain.SyncState;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DocumentServiceTest {
    @TempDir Path directory;

    @Test void editsAreAutomaticallySavedAndCanonicalAliasesShareOneSession() throws Exception {
        Path file = Files.writeString(directory.resolve("notes"), "initial");
        Path link = Files.createSymbolicLink(directory.resolve("link"), file);
        try (Harness app = new Harness()) {
            DocumentSession session = app.service.open(file).get(5, TimeUnit.SECONDS);
            assertSame(session, app.service.open(link).get(5, TimeUnit.SECONDS));
            session.replace(0, 7, "changed");
            // No flush or Save call is needed to start persistence.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.readString(file).equals("changed") && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("changed", Files.readString(file));
            assertEquals(SyncState.SYNCHRONIZED, session.flush().get(5, TimeUnit.SECONDS).state());
            assertTrue(app.repository.load().isEmpty());
        }
    }

    @Test void cleanExternalChangesReloadAndIndependentConcurrentChangesMerge() throws Exception {
        Path file = Files.writeString(directory.resolve("notes"), "one\ntwo\nthree\n");
        try (Harness app = new Harness()) {
            DocumentSession session = app.service.open(file).get(5, TimeUnit.SECONDS);
            Files.writeString(file, "external\ntwo\nthree\n");
            assertEquals("external\ntwo\nthree\n", session.flush().get(5, TimeUnit.SECONDS).text());
            CountDownLatch blocked = app.blockIo();
            session.replaceText("LOCAL\ntwo\nthree\n");
            Files.writeString(file, "external\ntwo\nREMOTE\n");
            blocked.countDown();
            assertEquals("LOCAL\ntwo\nREMOTE\n", session.flush().get(5, TimeUnit.SECONDS).text());
            assertEquals("LOCAL\ntwo\nREMOTE\n", Files.readString(file));
        }
    }

    @Test void conflictsStayPausedAcrossFurtherTypingAndRestartUntilExplicitResolution() throws Exception {
        Path file = Files.writeString(directory.resolve("notes"), "base");
        java.util.UUID identity;
        try (Harness app = new Harness()) {
            DocumentSession session = app.service.open(file).get(5, TimeUnit.SECONDS);
            identity = session.id();
            CountDownLatch blocked = app.blockIo();
            session.replaceText("mine");
            Files.writeString(file, "theirs");
            blocked.countDown();
            assertEquals(SyncState.CONFLICT, session.flush().get(5, TimeUnit.SECONDS).state());
            session.replaceText("my latest");
            session.flush().get(5, TimeUnit.SECONDS);
            assertEquals("theirs", Files.readString(file));
            assertEquals("my latest", app.repository.load().getFirst().text());
        }
        try (Harness restored = new Harness()) {
            List<DocumentSession> recovered = restored.service.restoreDrafts().get(5, TimeUnit.SECONDS);
            assertEquals(1, recovered.size());
            DocumentSession session = recovered.getFirst();
            assertEquals(identity, session.id());
            assertEquals(SyncState.CONFLICT, session.flush().get(5, TimeUnit.SECONDS).state());
            assertEquals("theirs", Files.readString(file));
            session.overwriteDisk().get(5, TimeUnit.SECONDS);
            assertEquals("my latest", Files.readString(file));
            assertEquals(SyncState.SYNCHRONIZED, session.state());
            assertTrue(restored.repository.load().isEmpty());
        }
    }

    @Test void overwriteChoiceRejectsNewerExternalVersion() throws Exception {
        Path file = Files.writeString(directory.resolve("notes"), "base");
        try (Harness app = new Harness()) {
            DocumentSession session = app.service.open(file).get(5, TimeUnit.SECONDS);
            CountDownLatch first = app.blockIo();
            session.replaceText("mine");
            Files.writeString(file, "theirs");
            first.countDown();
            session.flush().get(5, TimeUnit.SECONDS);
            CountDownLatch second = app.blockIo();
            CompletableFuture<?> decision = session.overwriteDisk();
            Files.writeString(file, "even newer");
            second.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> decision.get(5, TimeUnit.SECONDS));
            assertEquals("even newer", Files.readString(file));
            assertEquals(SyncState.CONFLICT, session.flush().get(5, TimeUnit.SECONDS).state());
            assertEquals("even newer", session.snapshot().externalText());
        }
    }

    @Test void externalDeletionKeepsTextAndNeverRecreatesFileWithoutChoice() throws Exception {
        Path file = Files.writeString(directory.resolve("notes"), "precious");
        try (Harness app = new Harness()) {
            DocumentSession session = app.service.open(file).get(5, TimeUnit.SECONDS);
            Files.delete(file);
            assertEquals(SyncState.DELETED, session.flush().get(5, TimeUnit.SECONDS).state());
            session.replaceText("more precious");
            session.flush().get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(file));
            assertEquals("more precious", app.repository.load().getFirst().text());
            session.overwriteDisk().get(5, TimeUnit.SECONDS);
            assertEquals("more precious", Files.readString(file));
        }
    }

    @Test void draftsSurviveCloseAndAssigningAPathDoesNotOverwriteExistingFile() throws Exception {
        Path existing = Files.writeString(directory.resolve("existing"), "keep");
        try (Harness app = new Harness()) {
            DocumentSession draft = app.service.createDraft();
            draft.replaceText("unsaved named later");
            draft.close().get(5, TimeUnit.SECONDS);
            assertEquals("unsaved named later", app.repository.load().getFirst().text());
        }
        try (Harness restored = new Harness()) {
            DocumentSession draft = restored.service.restoreDrafts().get(5, TimeUnit.SECONDS).getFirst();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> draft.assignPath(existing).get(5, TimeUnit.SECONDS));
            assertEquals("keep", Files.readString(existing));
            Path target = directory.resolve("named");
            draft.assignPath(target).get(5, TimeUnit.SECONDS);
            assertEquals(target, draft.path());
            assertEquals("unsaved named later", Files.readString(target));
        }
    }

    @Test void pausedMoveNeverRecreatesOldPathAndDeletionRemovesRecovery() throws Exception {
        Path file = Files.writeString(directory.resolve("old"), "first");
        Path moved = directory.resolve("new");
        try (Harness app = new Harness()) {
            DocumentSession session = app.service.open(file).get(5, TimeUnit.SECONDS);
            session.replaceText("latest");
            session.flush().get(5, TimeUnit.SECONDS);
            session.pause().get(5, TimeUnit.SECONDS);
            Files.move(file, moved);
            session.replaceText("while moving");
            session.remapPath(moved).get(5, TimeUnit.SECONDS);
            session.resume();
            session.flush().get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(file));
            assertEquals("while moving", Files.readString(moved));
            session.pause().get(5, TimeUnit.SECONDS);
            Files.delete(moved);
            session.discardRecoveryAndClose().get(5, TimeUnit.SECONDS);
            assertTrue(app.repository.load().isEmpty());
            assertTrue(app.service.sessions().isEmpty());
        }
    }

    @Test void externalTextMutationAndListenerRunInOneUiTurnAndStaleMergeRetries() throws Exception {
        Path file = Files.writeString(directory.resolve("view-race"), "one\ntwo\n");
        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> uiTurns = new java.util.concurrent.ConcurrentLinkedQueue<>();
        DocumentService service = new DocumentService(new NioDocumentStore(1024 * 1024),
                new FileDraftRepository(directory.resolve("race-recovery")), io, scheduler, uiTurns::add, Duration.ofHours(1));
        try {
            DocumentSession session = service.open(file).get(5, TimeUnit.SECONDS);
            java.util.concurrent.atomic.AtomicReference<String> displayed = new java.util.concurrent.atomic.AtomicReference<>(session.text());
            session.addListener(snapshot -> {
                assertEquals(snapshot.text(), session.text());
                displayed.set(snapshot.text());
            });
            Files.writeString(file, "one\nTWO\n");
            CompletableFuture<?> flush = session.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (uiTurns.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(uiTurns.isEmpty());
            assertEquals("one\ntwo\n", session.text(), "IO must not change the buffer ahead of its UI adapter");
            session.replace(0, 0, "LOCAL ");
            displayed.set("LOCAL one\ntwo\n");
            while (!flush.isDone() && System.nanoTime() < deadline) {
                Runnable turn = uiTurns.poll();
                if (turn != null) turn.run();
                else Thread.sleep(5);
            }
            flush.get(1, TimeUnit.SECONDS);
            while (!uiTurns.isEmpty()) uiTurns.remove().run();
            assertEquals("LOCAL one\nTWO\n", displayed.get());
            assertEquals(displayed.get(), session.text());
            assertEquals(displayed.get(), Files.readString(file));
            service.close().get(5, TimeUnit.SECONDS);
        } finally { scheduler.shutdownNow(); io.shutdownNow(); }
    }

    @Test void reopeningDeferredConflictRestoresItsBufferAndIdentity() throws Exception {
        Path file = Files.writeString(directory.resolve("reopen"), "base");
        try (Harness app = new Harness()) {
            DocumentSession first = app.service.open(file).get(5, TimeUnit.SECONDS);
            CountDownLatch block = app.blockIo();
            first.replaceText("mine");
            Files.writeString(file, "theirs");
            block.countDown();
            first.flush().get(5, TimeUnit.SECONDS);
            first.close().get(5, TimeUnit.SECONDS);
            DocumentSession reopened = app.service.open(file).get(5, TimeUnit.SECONDS);
            assertEquals(first.id(), reopened.id());
            assertEquals("mine", reopened.text());
            assertEquals(SyncState.CONFLICT, reopened.flush().get(5, TimeUnit.SECONDS).state());
            assertEquals("theirs", Files.readString(file));
        }
    }

    @Test void typingDuringAnInFlightWriteSavesLatestRevisionNextWithoutFalseConflict() throws Exception {
        Path file = Files.writeString(directory.resolve("in-flight"), "initial");
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        NioDocumentStore disk = new NioDocumentStore(1024 * 1024);
        dev.rilvune.document.domain.DocumentStore delayed = new dev.rilvune.document.domain.DocumentStore() {
            @Override public Path canonicalPath(Path path) throws java.io.IOException { return disk.canonicalPath(path); }
            @Override public dev.rilvune.document.domain.DiskSnapshot read(Path path) throws java.io.IOException { return disk.read(path); }
            @Override public dev.rilvune.document.domain.DiskSnapshot write(Path path, String text, dev.rilvune.document.domain.TextFormat format,
                    dev.rilvune.document.domain.DiskSnapshot expected) throws java.io.IOException {
                enteredWrite.countDown();
                try { assertTrue(releaseWrite.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { throw new java.io.IOException(exception); }
                return disk.write(path, text, format, expected);
            }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DocumentService service = new DocumentService(delayed, new FileDraftRepository(directory.resolve("flight-recovery")),
                io, scheduler, Runnable::run, Duration.ofHours(1));
        try {
            DocumentSession session = service.open(file).get(5, TimeUnit.SECONDS);
            session.replaceText("first snapshot");
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS));
            session.replaceText("second snapshot");
            session.replaceText("latest snapshot");
            assertEquals(SyncState.SYNCHRONIZING, session.state());
            CompletableFuture<?> finished = session.flush();
            assertFalse(finished.isDone());
            releaseWrite.countDown();
            finished.get(5, TimeUnit.SECONDS);
            assertEquals("latest snapshot", Files.readString(file));
            assertEquals(SyncState.SYNCHRONIZED, session.state());
            service.close().get(5, TimeUnit.SECONDS);
        } finally { releaseWrite.countDown(); scheduler.shutdownNow(); io.shutdownNow(); }
    }

    @Test void recoveryStorageFailureIsVisibleButDoesNotDisableSourceAutosave() throws Exception {
        Path file = Files.writeString(directory.resolve("independent"), "initial");
        dev.rilvune.document.domain.DraftRepository broken = new dev.rilvune.document.domain.DraftRepository() {
            @Override public void save(dev.rilvune.document.domain.RecoveryDraft draft) throws java.io.IOException {
                throw new java.io.IOException("Profile disk is full");
            }
            @Override public List<dev.rilvune.document.domain.RecoveryDraft> load() { return List.of(); }
            @Override public void remove(java.util.UUID id) throws java.io.IOException { throw new java.io.IOException("Profile disk is full"); }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DocumentService service = new DocumentService(new NioDocumentStore(1024 * 1024), broken,
                io, scheduler, Runnable::run, Duration.ofHours(1));
        try {
            DocumentSession session = service.open(file).get(5, TimeUnit.SECONDS);
            session.replaceText("safely saved");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.flush().get(5, TimeUnit.SECONDS));
            assertEquals("safely saved", Files.readString(file));
            assertEquals(SyncState.SYNCHRONIZED, session.state(), "Source autosave succeeded independently of recovery");
            assertTrue(session.snapshot().recoveryFailed());
            assertTrue(session.snapshot().details().contains("Profile disk is full"));
            assertEquals("safely saved", session.text());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> service.close().get(5, TimeUnit.SECONDS));
        } finally { scheduler.shutdownNow(); io.shutdownNow(); }
    }

    private final class Harness implements AutoCloseable {
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final FileDraftRepository repository = new FileDraftRepository(directory.resolve("recovery"));
        private final DocumentService service = new DocumentService(new NioDocumentStore(1024 * 1024), repository,
                io, scheduler, Runnable::run, Duration.ofHours(1));

        CountDownLatch blockIo() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            io.execute(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            return release;
        }

        @Override public void close() throws Exception {
            try { service.close().get(5, TimeUnit.SECONDS); }
            finally { scheduler.shutdownNow(); io.shutdownNow(); }
        }
    }
}
