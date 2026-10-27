package dev.editor.document.infrastructure;

import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.domain.SyncState;
import dev.editor.document.domain.TextFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FileDraftRepositoryTest {
    @TempDir Path directory;

    @Test void damagedRecordDoesNotPreventLoadingHealthyDrafts() throws Exception {
        var repository = new FileDraftRepository(directory);
        var good = draft("healthy text");
        try {
            repository.save(good);
            Path damaged = directory.resolve(UUID.randomUUID() + ".draft");
            byte[] original = {1, 2, 3};
            Files.write(damaged, original);
            assertEquals(good.text(), repository.load().getFirst().text());
            assertArrayEquals(original, Files.readAllBytes(damaged));
        } finally { repository.release(good.id()); }
    }

    @Test void failedOversizedCheckpointPreservesPreviouslyReadableRecord() throws Exception {
        var repository = new FileDraftRepository(directory, 64);
        var original = draft("previous checkpoint");
        try {
            repository.save(original);
            byte[] previous = Files.readAllBytes(directory.resolve(original.id() + ".draft"));
            var oversized = new RecoveryDraft(original.id(), null, "x".repeat(65), 1,
                    original.format(), null, null, SyncState.DRAFT, null);
            assertThrows(IOException.class, () -> repository.save(oversized));
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve(original.id() + ".draft")));
            assertEquals(original.text(), repository.find(original.id()).orElseThrow().text());
        } finally { repository.release(original.id()); }
    }

    @Test void readerAndWriterAgreeAtTheLimitAndOnEveryStoredString() throws Exception {
        var repository = new FileDraftRepository(directory, 64);
        var original = draft("x".repeat(64));
        try {
            repository.save(original);
            assertEquals(original, repository.find(original.id()).orElseThrow());
            byte[] previous = Files.readAllBytes(directory.resolve(original.id() + ".draft"));
            var tooLong = "x".repeat(65);
            var format = original.format();
            var snapshots = java.util.List.of(
                    new dev.editor.document.domain.DiskSnapshot(tooLong, "", format, "hash"),
                    new dev.editor.document.domain.DiskSnapshot("", tooLong, format, "hash"),
                    new dev.editor.document.domain.DiskSnapshot("", "", format, tooLong));
            var proposals = new java.util.ArrayList<RecoveryDraft>();
            proposals.add(new RecoveryDraft(original.id(), Path.of(tooLong), "text", 1, format, null, null, SyncState.DRAFT, null));
            proposals.add(new RecoveryDraft(original.id(), null, "text", 1, format, null, null, SyncState.DRAFT, tooLong));
            proposals.add(new RecoveryDraft(original.id(), null, null, 1, format, null, null, SyncState.DRAFT, null));
            for (var snapshot : snapshots) {
                proposals.add(new RecoveryDraft(original.id(), null, "text", 1, format, snapshot, null, SyncState.DRAFT, null));
                proposals.add(new RecoveryDraft(original.id(), null, "text", 1, format, null, snapshot, SyncState.DRAFT, null));
            }
            for (var proposal : proposals) {
                assertThrows(IOException.class, () -> repository.save(proposal));
                assertArrayEquals(previous, Files.readAllBytes(directory.resolve(original.id() + ".draft")));
            }
        } finally { repository.release(original.id()); }
    }

    @Test void malformedRecordsAreReportedWithoutModifyingThemOrTheHealthyRecord() throws Exception {
        var repository = new FileDraftRepository(directory);
        var healthy = draft("healthy");
        repository.save(healthy); repository.release(healthy.id());
        java.util.List<java.util.function.UnaryOperator<byte[]>> corruptions = java.util.List.of(
                bytes -> { java.nio.ByteBuffer.wrap(bytes).putInt(0, 0); return bytes; },
                bytes -> java.util.Arrays.copyOf(bytes, bytes.length - 2),
                bytes -> { java.nio.ByteBuffer.wrap(bytes).putInt(24, -2); return bytes; },
                bytes -> { java.nio.ByteBuffer.wrap(bytes).putInt(24, -1); return bytes; },
                bytes -> { java.nio.ByteBuffer.wrap(bytes).putInt(24, 32 * 1024 * 1024 + 1); return bytes; },
                bytes -> { java.nio.ByteBuffer.wrap(bytes).putInt(24, 32 * 1024 * 1024); return bytes; },
                bytes -> { System.arraycopy("WRONG".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, bytes.length - 9, 5); return bytes; },
                bytes -> java.util.Arrays.copyOf(bytes, bytes.length + 1));
        int count = 0;
        for (var corruption : corruptions) {
            UUID id = UUID.randomUUID();
            byte[] bytes = corruption.apply(encodedRecord(id, null, "text"));
            Path file = Files.write(directory.resolve(id + ".draft"), bytes);
            var scan = repository.scan();
            assertEquals(java.util.List.of(healthy), scan.drafts());
            assertEquals(++count, scan.problems().size());
            assertTrue(scan.problems().stream().anyMatch(problem -> file.equals(problem.file())));
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
        UUID id = UUID.randomUUID();
        Path invalidPath = Files.write(directory.resolve(id + ".draft"), encodedRecord(id, "\0", "text"));
        Path mismatched = Files.write(directory.resolve(UUID.randomUUID() + ".draft"), encodedRecord(UUID.randomUUID(), null, "text"));
        var scan = repository.scan();
        assertEquals(java.util.List.of(healthy), scan.drafts());
        assertTrue(scan.problems().stream().anyMatch(problem -> invalidPath.equals(problem.file())));
        assertTrue(scan.problems().stream().anyMatch(problem -> mismatched.equals(problem.file())));
    }

    @Test void failedAtomicPublicationKeepsPreviousRecordAndCleansOnlyItsTemporaryFile() throws Exception {
        var original = draft("previous");
        var repository = new FileDraftRepository(directory, 64);
        repository.save(original); repository.release(original.id());
        byte[] previous = Files.readAllBytes(directory.resolve(original.id() + ".draft"));
        for (boolean unsupported : new boolean[] {false, true}) {
            var failing = new FileDraftRepository(directory, 64, (temporary, target) -> {
                if (unsupported) throw new java.nio.file.AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "unsupported");
                throw new IOException("Publication failed");
            });
            try {
                var update = new RecoveryDraft(original.id(), null, "changed", 1, original.format(), null, null, SyncState.DRAFT, null);
                assertThrows(IOException.class, () -> failing.save(update));
                assertArrayEquals(previous, Files.readAllBytes(directory.resolve(original.id() + ".draft")));
                assertEquals(original, failing.find(original.id()).orElseThrow());
                try (var files = Files.list(directory)) { assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp"))); }
            } finally { failing.release(original.id()); }
        }
    }

    @Test void edr1FixturePreservesBomSeparatorsAndIncompleteSurrogate() throws Exception {
        UUID id = UUID.fromString("12345678-1234-5678-1234-567812345678");
        byte[] fixture = java.util.Base64.getDecoder().decode("RURSMRI0VngSNFZ4EjRWeBI0Vnj/////AAAAAwBhAArYAAAAAAAAAAAFAQAFTUlYRUQBAAAAAgBhAAoAAAADAGEADQAKAQAEQ1JMRgAAAAsAZgBpAG4AZwBlAHIAcAByAGkAbgB0AAAFRFJBRlT/////");
        Path file = Files.write(directory.resolve(id + ".draft"), fixture);
        var repository = new FileDraftRepository(directory);
        try {
            var restored = repository.find(id).orElseThrow();
            assertEquals("a\n\uD800", restored.text());
            assertTrue(restored.format().byteOrderMark());
            assertEquals(dev.editor.document.domain.LineEnding.MIXED, restored.format().lineEnding());
            assertEquals("a\r\n", restored.base().rawText());
            repository.save(restored);
            assertArrayEquals(fixture, Files.readAllBytes(file));
        } finally { repository.release(id); }
    }

    @Test void missingDirectoryIsEmptyButWrongTypeAndSymbolicRecordsAreWarnings() throws Exception {
        var missing = new FileDraftRepository(directory.resolve("missing"));
        assertTrue(missing.scan().drafts().isEmpty());
        assertTrue(missing.scan().problems().isEmpty());
        Path wrongType = Files.writeString(directory.resolve("not-a-directory"), "content");
        assertThrows(IOException.class, () -> new FileDraftRepository(wrongType).scan());
        Path target = Files.writeString(directory.resolve("original"), "unchanged");
        Path link = Files.createSymbolicLink(directory.resolve(UUID.randomUUID() + ".draft"), target.getFileName());
        var scan = new FileDraftRepository(directory).scan();
        assertEquals(1, scan.problems().size());
        assertEquals(link, scan.problems().getFirst().file());
        assertEquals("unchanged", Files.readString(target));
    }

    @Test void oversizedLiveDraftStaysOpenAndRecoversAfterTextIsReduced() throws Exception {
        var repository = new FileDraftRepository(directory, 256);
        try (var io = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            var documents = new dev.editor.document.application.DocumentService(new NioDocumentStore(10000), repository,
                    io, scheduler, Runnable::run, java.time.Duration.ofDays(1));
            var session = documents.createDraft();
            try {
                session.replaceText("previous checkpoint"); session.flush().get(5, java.util.concurrent.TimeUnit.SECONDS);
                byte[] previous = Files.readAllBytes(directory.resolve(session.id() + ".draft"));
                session.replaceText("x".repeat(257));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> session.flush().get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(session.snapshot().recoveryFailed());
                assertTrue(session.snapshot().needsAttention());
                assertTrue(session.snapshot().details().contains("256"));
                assertEquals("x".repeat(257), session.text());
                assertArrayEquals(previous, Files.readAllBytes(directory.resolve(session.id() + ".draft")));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> session.close().get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(session.isClosed());
                session.replaceText("corrected");
                var saved = session.flush().get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(saved.recoveryFailed());
                assertFalse(saved.needsAttention());
                assertEquals("corrected", repository.find(session.id()).orElseThrow().text());
            } finally {
                if (session.snapshot().recoveryFailed()) session.replaceText("safe cleanup");
                documents.close().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    private byte[] encodedRecord(UUID id, String path, String text) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
            output.writeInt(0x45445231); output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits());
            writeString(output, path); writeString(output, text); output.writeLong(0);
            output.writeBoolean(false); output.writeUTF("LF");
            output.writeBoolean(false); output.writeBoolean(false); output.writeUTF("DRAFT"); writeString(output, null);
        }
        return bytes.toByteArray();
    }
    private void writeString(java.io.DataOutputStream output, String text) throws IOException {
        output.writeInt(text == null ? -1 : text.length());
        if (text != null) output.writeChars(text);
    }

    private RecoveryDraft draft(String text) {
        return new RecoveryDraft(UUID.randomUUID(), null, text, 0, TextFormat.utf8Lf(), null, null, SyncState.DRAFT, null);
    }
}
