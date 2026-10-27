package dev.editor.document.infrastructure;

import dev.editor.document.domain.FileChangedException;
import dev.editor.document.domain.LineEnding;
import dev.editor.document.domain.TextFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NioDocumentStoreTest {
    @TempDir Path directory;
    private final NioDocumentStore store = new NioDocumentStore(1024 * 1024);

    @Test void preservesBomCrlfAndExecutablePermissions() throws Exception {
        Path file = Files.writeString(directory.resolve("script"), "\uFEFFfirst\r\nsecond\r\n");
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(file, permissions);
        var original = store.read(file);
        assertEquals("first\nsecond\n", original.text());
        assertEquals(LineEnding.CRLF, original.format().lineEnding());
        store.write(file, "FIRST\nsecond\n", original.format(), original);
        assertEquals("\uFEFFFIRST\r\nsecond\r\n", Files.readString(file));
        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    @Test void preservesUnchangedMixedLineSeparators() throws Exception {
        Path file = Files.writeString(directory.resolve("mixed"), "first\r\nsecond\nthird\rfourth\r\n");
        var original = store.read(file);
        assertEquals(LineEnding.MIXED, original.format().lineEnding());
        store.write(file, "FIRST\nsecond\nthird\nFOURTH\n", original.format(), original);
        assertEquals("FIRST\r\nsecond\nthird\rFOURTH\r\n", Files.readString(file));
    }

    @Test void rejectsStaleExpectedRevisionAndNeverOverwritesOnCreate() throws Exception {
        Path file = Files.writeString(directory.resolve("notes"), "original");
        var original = store.read(file);
        Files.writeString(file, "external");
        assertThrows(FileChangedException.class, () -> store.write(file, "mine", original.format(), original));
        assertEquals("external", Files.readString(file));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> store.write(file, "mine", TextFormat.utf8Lf(), null));
        Files.delete(file);
        assertThrows(java.nio.file.NoSuchFileException.class, () -> store.write(file, "mine", original.format(), original));
        assertFalse(Files.exists(file));
    }

    @Test void resolvesOriginalLinksAndRejectsTargetReplacementByLink() throws Exception {
        Path file = Files.writeString(directory.resolve("target"), "original");
        Path link = Files.createSymbolicLink(directory.resolve("link"), file);
        assertEquals(file, store.canonicalPath(link));
        var original = store.read(file);
        store.write(store.canonicalPath(link), "new", original.format(), original);
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(link));
        assertThrows(IOException.class, () -> store.read(link));
    }

    @Test void rejectsOversizeBinaryAndMalformedUtf8WithoutTruncation() throws Exception {
        Path large = Files.writeString(directory.resolve("large"), "123456789");
        assertThrows(IOException.class, () -> new NioDocumentStore(8).read(large));
        assertThrows(IOException.class, () -> new NioDocumentStore(8).write(large, "1234567890", TextFormat.utf8Lf(), null));
        Path binary = Files.write(directory.resolve("binary"), new byte[] {0, 1});
        Path invalid = Files.write(directory.resolve("invalid"), new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(IOException.class, () -> store.read(binary));
        assertThrows(IOException.class, () -> store.read(invalid));
        assertEquals("123456789", Files.readString(large));
    }

    @Test void copiesMixedSeparatorsAndRetainsExplicitStyleBeforeFirstNewline() throws Exception {
        Path file = Files.writeString(directory.resolve("source"), "one\r\ntwo\n");
        var original = store.read(file);
        Path copy = directory.resolve("copy");
        store.createCopy(copy, "ONE\ntwo\n", original.format(), original);
        assertEquals("ONE\r\ntwo\n", Files.readString(copy));
        var empty = store.write(directory.resolve("empty"), "", new TextFormat(false, LineEnding.CRLF), null);
        assertEquals(LineEnding.CRLF, empty.format().lineEnding());
        assertThrows(IOException.class, () -> store.write(file, "bad\0text", original.format(), original));
        assertEquals("one\r\ntwo\n", Files.readString(file));
    }
}
