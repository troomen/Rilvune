package dev.editor.agents.infrastructure.opencode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeInstallerTest {
    @TempDir Path temporary;
    @Test void extractsOnlyTheExpectedNativeBinaryFromAnNpmTarball() throws Exception {
        Path archive = temporary.resolve("package.tgz"), binary = temporary.resolve("opencode");
        tar(archive, "package/bin/opencode", '0', 6); OpenCodeInstaller.extract(archive, binary);
        assertEquals("binary", Files.readString(binary));
    }
    @Test void rejectsLinksPathTraversalUnexpectedEntriesAndTruncatedBinaries() throws Exception {
        Path archive = temporary.resolve("package.tgz");
        for (String entry : new String[]{"../outside", "package/bin/../../outside", "opencode"}) {
            tar(archive, entry, '0', 6);
            assertThrows(IOException.class, () -> OpenCodeInstaller.extract(archive, temporary.resolve("opencode")));
        }
        tar(archive, "package/bin/opencode", '2', 6);
        assertThrows(IOException.class, () -> OpenCodeInstaller.extract(archive, temporary.resolve("opencode")));
        tar(archive, "package/bin/opencode", '0', 4096);
        assertThrows(IOException.class, () -> OpenCodeInstaller.extract(archive, temporary.resolve("opencode")));
        assertFalse(Files.exists(temporary.getParent().resolve("outside")));
    }
    @Test void missingRuntimeDoesNotDownloadAndUnsupportedPlatformFailsExplicitly() throws Exception {
        var installer = new OpenCodeInstaller(temporary, "unsupported"); assertTrue(installer.installed().isEmpty());
        assertThrows(IOException.class, () -> installer.install(ignored -> { }));
    }
    private static void tar(Path archive, String name, char type, int declaredSize) throws IOException {
        try (var stream = new GZIPOutputStream(Files.newOutputStream(archive))) {
            entry(stream, "package/package.json", '0', 2, "{}");
            entry(stream, name, type, declaredSize, "binary");
            stream.write(new byte[1024]);
        }
    }
    private static void entry(OutputStream stream, String path, char type, int size, String content) throws IOException {
        byte[] header = new byte[512], name = path.getBytes(); System.arraycopy(name, 0, header, 0, name.length);
        byte[] octal = String.format("%011o", size).getBytes(); System.arraycopy(octal, 0, header, 124, octal.length); header[156] = (byte)type;
        stream.write(header); stream.write(content.getBytes()); stream.write(new byte[(512 - content.length() % 512) % 512]);
    }
}
