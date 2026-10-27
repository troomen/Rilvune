package dev.rilvune.agents.infrastructure.opencode;

import dev.rilvune.platform.runtime.HostPlatform;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
        Path directory = temporary.resolve("not-created");
        var installer = new OpenCodeInstaller(directory, HostPlatform.detect("Mac OS X", "riscv64"));
        assertTrue(installer.installed().isEmpty());
        String reason = installer.unsupportedReason().orElseThrow();
        assertTrue(reason.contains("Mac OS X")); assertTrue(reason.contains("riscv64")); assertTrue(reason.contains("disabled"));
        assertEquals(reason, assertThrows(IOException.class, () -> installer.install(ignored -> fail("No download should start"))).getMessage());
        assertFalse(Files.exists(directory));
    }
    @Test void downloadsTheSelectedMacArchitectureAndReusesVerifiedInstallations() throws Exception {
        Path archive = temporary.resolve("package.tgz"); tar(archive, "package/bin/opencode", '0', 6);
        byte[] bytes = Files.readAllBytes(archive);
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            Properties properties = fixtureRelease(server, bytes);
            for (String arch : List.of("amd64", "x86_64", "arm64", "aarch64")) {
                var platform = HostPlatform.detect("Mac OS X", arch);
                var installer = new OpenCodeInstaller(temporary.resolve("bin"), platform, new OpenCodeRelease(properties));
                var progress = new ArrayList<String>();
                Path binary = installer.install(progress::add);
                assertEquals("binary", Files.readString(binary)); assertTrue(Files.isExecutable(binary));
                String target = platform.architecture() == HostPlatform.Architecture.X86_64 ? "darwin-x64-baseline" : "darwin-arm64";
                assertEquals(temporary.resolve("bin/2.1.3").resolve(target).resolve("opencode"), binary);
                assertEquals(binary, installer.installed().orElseThrow());
                assertTrue(installer.unsupportedReason().isEmpty());
                if (!progress.isEmpty()) assertTrue(progress.getFirst().contains("macOS / " + platform.architecture().id()));
            }
            assertEquals(List.of("/darwin-x64-baseline", "/darwin-arm64"), requests);
            // A catalog without a matching target cannot download even when its OS/CPU are known.
            Properties empty = new Properties(); empty.setProperty("version", "2.1.3");
            var unavailable = new OpenCodeInstaller(temporary.resolve("unavailable"), HostPlatform.detect("Mac OS X", "amd64"), new OpenCodeRelease(empty));
            assertThrows(IOException.class, () -> unavailable.install(ignored -> fail("No download should start")));
            assertEquals(2, requests.size()); assertFalse(Files.exists(temporary.resolve("unavailable")));
        } finally { server.stop(0); }
    }
    @Test void rejectsDamagedDownloadsWithoutPublishingAnInstallation() throws Exception {
        byte[] bytes = "damaged archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            Properties properties = fixtureRelease(server, bytes);
            properties.setProperty("darwin-x64-baseline.sha512", "0".repeat(128));
            Path directory = temporary.resolve("bin");
            var installer = new OpenCodeInstaller(directory, HostPlatform.detect("Mac OS X", "x86_64"), new OpenCodeRelease(properties));
            assertTrue(assertThrows(IOException.class, () -> installer.install(ignored -> { })).getMessage().contains("checksum mismatch"));
            assertTrue(installer.installed().isEmpty());
            try (var entries = Files.list(directory)) { assertEquals(List.of("install.lock"), entries.map(path -> path.getFileName().toString()).toList()); }
        } finally { server.stop(0); }
    }
    private static Properties fixtureRelease(HttpServer server, byte[] bytes) throws Exception {
        Properties properties = new Properties(); properties.setProperty("version", "2.1.3");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
        for (String[] pair : List.of(new String[]{"macos-x86_64", "darwin-x64-baseline"}, new String[]{"macos-arm64", "darwin-arm64"})) {
            properties.setProperty("platform." + pair[0], pair[1]);
            properties.setProperty(pair[1] + ".url", "http://127.0.0.1:" + server.getAddress().getPort() + "/" + pair[1]);
            properties.setProperty(pair[1] + ".sha512", digest);
        }
        return properties;
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
