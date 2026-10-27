package dev.rilvune.agents.infrastructure.opencode;

import dev.rilvune.agents.domain.AgentRuntimeInstaller;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Pinned native distribution, authenticated by an embedded archive digest; never executes installer scripts. */
public final class OpenCodeInstaller implements AgentRuntimeInstaller {
    private static final long ARCHIVE_LIMIT = 160L * 1024 * 1024, BINARY_LIMIT = 512L * 1024 * 1024;
    private final Path directory;
    private final String platform;
    private final Properties release = new Properties();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    public OpenCodeInstaller(Path directory) {
        this(directory, OpenCodeRuntimePaths.platform());
    }
    public OpenCodeInstaller(Path directory, String platform) {
        this.directory = directory; this.platform = platform;
        try (InputStream stream = OpenCodeInstaller.class.getResourceAsStream("/agents/opencode/release.properties")) {
            if (stream == null) throw new IOException("Missing OpenCode release manifest"); release.load(stream);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private Path executable() { return directory.resolve(release.getProperty("version")).resolve(platform).resolve(platform.startsWith("windows") ? "opencode.exe" : "opencode"); }
    @Override public Optional<Path> installed() throws Exception {
        Path binary = executable(), checksum = binary.resolveSibling("binary.sha256");
        if (!Files.isRegularFile(binary) || !Files.isRegularFile(checksum)) return Optional.empty();
        if (!digest(binary, "SHA-256").equals(Files.readString(checksum).strip())) throw new IOException("Installed OpenCode failed its integrity check; reinstall the component");
        return Optional.of(binary);
    }
    @Override public Path install(Consumer<String> progress) throws Exception {
        String url = release.getProperty(platform + ".url"), expected = release.getProperty(platform + ".sha512");
        if (url == null || expected == null) throw new IOException("No OpenCode build for " + platform);
        Files.createDirectories(directory);
        try (FileChannel channel = FileChannel.open(directory.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = null;
            for (int attempt = 0; attempt < 600 && lock == null; attempt++) {
                try { lock = channel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException busy) { }
                if (lock == null) { progress.accept("Waiting for another OpenCode installation…"); Thread.sleep(200); }
            }
            if (lock == null) throw new IOException("Another OpenCode installation is still running; retry shortly");
            try {
                try { Optional<Path> current = installed(); if (current.isPresent()) return current.orElseThrow(); } catch (IOException invalid) { /* Explicit reinstall repairs only this managed component. */ }
                Path staging = Files.createTempDirectory(directory, ".install-");
                try {
                    Path archive = staging.resolve("opencode.tgz");
                    URI uri = URI.create(url);
                    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
                    HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream stream = response.body()) {
                        if (response.statusCode() != 200) throw new IOException("OpenCode download failed: HTTP " + response.statusCode());
                        try (OutputStream target = Files.newOutputStream(archive)) { copy(stream, target, ARCHIVE_LIMIT, progress); }
                    }
                    progress.accept("Verifying OpenCode " + release.getProperty("version") + "…");
                    if (!digest(archive, "SHA-512").equals(expected)) throw new IOException("OpenCode archive checksum mismatch");
                    Path binary = staging.resolve(platform.startsWith("windows") ? "opencode.exe" : "opencode");
                    extract(archive, binary);
                    if (!platform.startsWith("windows") && !binary.toFile().setExecutable(true, true)) throw new IOException("Cannot make OpenCode executable");
                    Path destination = executable(); Files.createDirectories(destination.getParent());
                    String checksum = digest(binary, "SHA-256");
                    Files.move(binary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    Path marker = staging.resolve("binary.sha256"); Files.writeString(marker, checksum);
                    Files.move(marker, destination.resolveSibling("binary.sha256"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    try (InputStream license = OpenCodeInstaller.class.getResourceAsStream("/agents/opencode/LICENSE.txt")) {
                        if (license != null) Files.copy(license, destination.resolveSibling("LICENSE.txt"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    return destination;
                } finally {
                    try (var paths = Files.walk(staging)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
                }
            } finally { lock.release(); }
        }
    }
    /** Official V2 native packages contain only package metadata and one standalone executable. */
    static void extract(Path archive, Path binary) throws IOException {
        String expected = "package/bin/" + binary.getFileName();
        try (InputStream stream = new GZIPInputStream(Files.newInputStream(archive))) {
            for (int entries = 0; entries < 100; entries++) {
                byte[] header = stream.readNBytes(512);
                if (header.length != 512) break;
                String name = ascii(header, 0, 100);
                if (name.isEmpty()) break;
                long size;
                try { size = Long.parseLong(ascii(header, 124, 12).strip(), 8); }
                catch (NumberFormatException invalid) { throw new IOException("Invalid tar entry size", invalid); }
                if (size < 0 || size > BINARY_LIMIT) throw new IOException("OpenCode archive entry exceeds limit");
                boolean regular = header[156] == 0 || header[156] == '0';
                if (name.equals(expected) && regular && ascii(header, 345, 155).isEmpty()) {
                    try (OutputStream output = Files.newOutputStream(binary, StandardOpenOption.CREATE_NEW)) {
                        long left = size; byte[] buffer = new byte[65536];
                        while (left > 0) {
                            int count = stream.read(buffer, 0, (int)Math.min(left, buffer.length));
                            if (count < 0) throw new EOFException("Truncated OpenCode binary");
                            output.write(buffer, 0, count); left -= count;
                        }
                    }
                    return;
                }
                boolean metadata = regular && name.equals("package/package.json") && size <= 65536;
                boolean extendedHeader = (header[156] == 'x' || header[156] == 'g') && size <= 65536;
                if (!metadata && !extendedHeader) throw new IOException("Unexpected OpenCode archive entry: " + name);
                stream.skipNBytes((size + 511) / 512 * 512);
            }
        }
        throw new IOException("OpenCode executable not found in archive");
    }
    private static String ascii(byte[] bytes, int start, int count) { int end = start; while (end < start + count && bytes[end] != 0) end++; return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.US_ASCII); }
    private static void copy(InputStream input, OutputStream output, long limit, Consumer<String> progress) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0, reported = -1; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Installation cancelled");
            total += count; if (total > limit) throw new IOException("OpenCode download exceeds limit"); output.write(buffer, 0, count);
            long megabytes = total / 1048576; if (megabytes / 4 != reported) { reported = megabytes / 4; progress.accept("Downloading OpenCode · " + megabytes + " MiB"); }
        }
    }
    private static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
