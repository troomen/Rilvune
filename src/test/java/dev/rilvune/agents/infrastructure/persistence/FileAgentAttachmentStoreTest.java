package dev.rilvune.agents.infrastructure.persistence;

import dev.rilvune.agents.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class FileAgentAttachmentStoreTest {
    @TempDir Path temporary;
    private FileAgentAttachmentStore store() { return new FileAgentAttachmentStore(temporary.resolve("profile")); }

    @Test void snapshotsSurviveSourceDeletionAndStoreRecreation() throws Exception {
        Path source = temporary.resolve("source.java"); Files.writeString(source, "class Sample { } // Пример");
        var store = store(); var attachment = store.importFile(source); Files.delete(source);
        var reopened = store(); reopened.verify(attachment);
        assertEquals("source.java", attachment.name()); assertEquals("text/plain", attachment.mimeType());
        assertEquals("class Sample { } // Пример", Files.readString(reopened.resolve(attachment)));
        assertTrue(store.reference(store.resolve(attachment).toUri().toString()).isPresent());
        assertTrue(store.reference(temporary.resolve("untrusted.png").toUri().toString()).isEmpty());
        assertTrue(store.reference("https://example.com/image.png").isEmpty());
        assertTrue(store.reference("file:///invalid/%xx").isEmpty());
    }
    @Test void identicalFilesDeduplicateWithoutLosingDisplayNames() throws Exception {
        Path first = temporary.resolve("first.txt"), second = temporary.resolve("second.md");
        Files.writeString(first, "same snapshot"); Files.writeString(second, "same snapshot");
        var store = store(); var a = store.importFile(first); var b = store.importFile(second);
        assertEquals(a.id(), b.id()); assertNotEquals(a.name(), b.name());
        try (var files = Files.list(store.resolve(a).getParent())) { assertEquals(1, files.count()); }
    }
    @Test void clipboardImagesArePngFilesWithPrivatePermissions() throws Exception {
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png);
        var store = store(); var attachment = store.saveImage(png.toByteArray());
        assertTrue(attachment.image()); assertEquals("image/png", attachment.mimeType()); assertTrue(attachment.id().endsWith(".png"));
        Path file = store.resolve(attachment); assertArrayEquals(png.toByteArray(), Files.readAllBytes(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        }
    }
    @Test void unsupportedBinaryDirectoriesOversizeAndNonPngClipboardAreRejected() throws Exception {
        var store = store();
        assertThrows(IOException.class, () -> store.importFile(temporary));
        assertThrows(IOException.class, () -> store.importContent("binary", new byte[] {0, 1, 2}));
        assertThrows(IOException.class, () -> store.importContent("bad UTF8", new byte[] {(byte)0xff}));
        assertThrows(IOException.class, () -> store.saveImage("not png".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path huge = temporary.resolve("huge");
        try (var file = new java.io.RandomAccessFile(huge.toFile(), "rw")) { file.setLength(AgentAttachment.MAX_BYTES + 1); }
        assertThrows(IOException.class, () -> store.importFile(huge));
    }
    @Test void fileTypeComesFromBytesAndSvgIsText() throws Exception {
        var store = store();
        assertEquals("application/pdf", store.importContent("misleading.txt", "%PDF-1.7\nfixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)).mimeType());
        assertEquals("text/plain", store.importContent("drawing.svg", "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)).mimeType());
    }
    @Test void changedOrMissingSnapshotsAreNeverSilentlySent() throws Exception {
        var store = store(); var attachment = store.importContent("note", new byte[] {'a', 'b'});
        Files.write(store.resolve(attachment), new byte[] {'c', 'd'});
        assertThrows(IOException.class, () -> store.verify(attachment));
        Files.delete(store.resolve(attachment)); assertThrows(IOException.class, () -> store.verify(attachment));
        assertThrows(IllegalArgumentException.class, () -> new AgentAttachment("../../outside.png", "bad", "image/png", 1));
    }
    @Test void promptLimitsApplyToAggregateBytesAndCountAndAllowImageOnlyTurns() {
        var attachment = new AgentAttachment("a".repeat(64) + ".png", "image.png", "image/png", 12 * 1024 * 1024);
        assertDoesNotThrow(() -> new AgentPrompt("", List.of(attachment)));
        assertThrows(IllegalArgumentException.class, () -> new AgentPrompt("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AgentPrompt("hi", List.of(attachment, attachment)));
        var tiny = new AgentAttachment("b".repeat(64) + ".txt", "note", "text/plain", 1);
        assertThrows(IllegalArgumentException.class, () -> new AgentPrompt("hi", java.util.Collections.nCopies(11, tiny)));
    }
}
