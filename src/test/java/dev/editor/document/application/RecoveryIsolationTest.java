package dev.editor.document.application;

import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.domain.SyncState;
import dev.editor.document.domain.TextFormat;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class RecoveryIsolationTest {
    @TempDir Path directory;

    @Test void unrelatedDamagedRecoveryDoesNotBlockFileOpenOrHealthyRestoration() throws Exception {
        Path drafts = Files.createDirectory(directory.resolve("drafts"));
        Path damaged = Files.write(drafts.resolve(UUID.randomUUID() + ".draft"), new byte[] {0, 1});
        var repository = new FileDraftRepository(drafts);
        var healthy = new RecoveryDraft(UUID.randomUUID(), null, "healthy draft", 1, TextFormat.utf8Lf(), null, null, SyncState.DRAFT, null);
        repository.save(healthy); repository.release(healthy.id());
        Path file = Files.writeString(directory.resolve("file.txt"), "source text");
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var documents = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
            try {
                assertEquals("source text", documents.open(file).get(5, TimeUnit.SECONDS).text());
                assertTrue(documents.recoveryProblems().stream().anyMatch(problem -> damaged.equals(problem.file())));
                assertEquals("healthy draft", documents.restoreDrafts().get(5, TimeUnit.SECONDS).getFirst().text());
                assertArrayEquals(new byte[] {0, 1}, Files.readAllBytes(damaged));
            } finally { documents.close().get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void recordThatBecomesUnreadableAfterClaimDoesNotBlockTheNextHealthyDraft() throws Exception {
        Path drafts = Files.createDirectory(directory.resolve("drafts"));
        var storage = new FileDraftRepository(drafts);
        var damaged = new RecoveryDraft(UUID.randomUUID(), null, "will become unreadable", 1, TextFormat.utf8Lf(), null, null, SyncState.DRAFT, null);
        var healthy = new RecoveryDraft(UUID.randomUUID(), null, "healthy", 1, TextFormat.utf8Lf(), null, null, SyncState.DRAFT, null);
        storage.save(damaged); storage.release(damaged.id()); storage.save(healthy); storage.release(healthy.id());
        var repository = corruptOnClaim(storage, drafts, damaged.id());
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var documents = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
            try {
                var restored = documents.restoreDrafts().get(5, TimeUnit.SECONDS);
                assertEquals(1, restored.size());
                assertEquals(healthy.id(), restored.getFirst().id());
                assertFalse(documents.recoveryProblems().isEmpty());
                var other = new FileDraftRepository(drafts);
                assertTrue(other.claim(damaged.id()), "The rejected record's claim must have been released");
                other.release(damaged.id());
                assertArrayEquals(new byte[] {1}, Files.readAllBytes(drafts.resolve(damaged.id() + ".draft")));
            } finally { documents.close().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void matchingRecordRejectedAfterClaimOpensCurrentSourceWithFreshIdentity() throws Exception {
        Path drafts = Files.createDirectory(directory.resolve("drafts"));
        Path file = Files.writeString(directory.resolve("file.txt"), "current source");
        var storage = new FileDraftRepository(drafts);
        var old = new RecoveryDraft(UUID.randomUUID(), file, "old recovered text", 1, TextFormat.utf8Lf(), null, null, SyncState.CONFLICT, null);
        storage.save(old); storage.release(old.id());
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var documents = new DocumentService(new NioDocumentStore(10000), corruptOnClaim(storage, drafts, old.id()),
                    io, scheduler, Runnable::run, Duration.ofDays(1));
            try {
                var opened = documents.open(file).get(5, TimeUnit.SECONDS);
                assertEquals("current source", opened.text());
                assertNotEquals(old.id(), opened.id());
                assertFalse(documents.recoveryProblems().isEmpty());
            } finally { documents.close().get(5, TimeUnit.SECONDS); }
        }
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(drafts.resolve(old.id() + ".draft")));
        assertEquals("current source", Files.readString(file));
    }

    @Test void storeDiscoveryFailureIsVisibleWithoutBlockingSourceOpenAndClearsAfterRetry() throws Exception {
        var storage = new FileDraftRepository(directory.resolve("drafts"));
        var failScan = new java.util.concurrent.atomic.AtomicBoolean(true);
        dev.editor.document.domain.DraftRepository repository = new dev.editor.document.domain.DraftRepository() {
            @Override public boolean claim(UUID id) throws java.io.IOException { return storage.claim(id); }
            @Override public void release(UUID id) throws java.io.IOException { storage.release(id); }
            @Override public void save(RecoveryDraft draft) throws java.io.IOException { storage.save(draft); }
            @Override public java.util.List<RecoveryDraft> load() throws java.io.IOException { return storage.load(); }
            @Override public Scan scan() throws java.io.IOException {
                if (failScan.get()) throw new java.io.IOException("Cannot list recovery directory");
                return storage.scan();
            }
            @Override public void remove(UUID id) throws java.io.IOException { storage.remove(id); }
        };
        Path file = Files.writeString(directory.resolve("file.txt"), "source");
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var documents = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
            try {
                assertEquals("source", documents.open(file).get(5, TimeUnit.SECONDS).text());
                assertEquals(1, documents.recoveryProblems().size());
                assertTrue(documents.recoveryProblems().getFirst().description().contains("Cannot list"));
                failScan.set(false);
                documents.restoreDrafts().get(5, TimeUnit.SECONDS);
                assertTrue(documents.recoveryProblems().isEmpty());
            } finally { documents.close().get(5, TimeUnit.SECONDS); }
        }
    }

    private dev.editor.document.domain.DraftRepository corruptOnClaim(FileDraftRepository storage, Path directory, UUID damaged) {
        return new dev.editor.document.domain.DraftRepository() {
            @Override public boolean claim(UUID id) throws java.io.IOException {
                boolean claimed = storage.claim(id);
                if (claimed && id.equals(damaged)) Files.write(directory.resolve(id + ".draft"), new byte[] {1});
                return claimed;
            }
            @Override public void release(UUID id) throws java.io.IOException { storage.release(id); }
            @Override public void save(RecoveryDraft draft) throws java.io.IOException { storage.save(draft); }
            @Override public java.util.List<RecoveryDraft> load() throws java.io.IOException { return storage.load(); }
            @Override public java.util.Optional<RecoveryDraft> find(UUID id) throws java.io.IOException { return storage.find(id); }
            @Override public void remove(UUID id) throws java.io.IOException { storage.remove(id); }
        };
    }
}
