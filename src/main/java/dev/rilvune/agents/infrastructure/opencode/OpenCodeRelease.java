package dev.rilvune.agents.infrastructure.opencode;

import dev.rilvune.platform.runtime.HostPlatform;
import java.io.*;
import java.net.URI;
import java.util.*;

/** Maps canonical host platforms to pinned upstream artifacts, independent of install paths and UI. */
final class OpenCodeRelease {
    record Distribution(String directory, URI url, String sha512, String executableName) { }
    private final String version;
    private final Map<String, Distribution> distributions;

    static OpenCodeRelease bundled() {
        try (InputStream stream = OpenCodeRelease.class.getResourceAsStream("/agents/opencode/release.properties")) {
            if (stream == null) throw new IOException("Missing OpenCode release manifest");
            Properties properties = new Properties(); properties.load(stream);
            return new OpenCodeRelease(properties);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    OpenCodeRelease(Properties properties) {
        version = required(properties, "version");
        if (!version.matches("[0-9]+(?:\\.[0-9A-Za-z_-]+)*")) throw new IllegalArgumentException("Invalid OpenCode version");
        Map<String, Distribution> values = new HashMap<>();
        for (String property : properties.stringPropertyNames()) {
            if (!property.startsWith("platform.")) continue;
            String platform = property.substring("platform.".length()), artifact = required(properties, property);
            if (!artifact.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IllegalArgumentException("Invalid OpenCode artifact: " + artifact);
            URI url = URI.create(required(properties, artifact + ".url"));
            if (!("https".equals(url.getScheme()) || "http".equals(url.getScheme())) || url.getHost() == null || url.getUserInfo() != null)
                throw new IllegalArgumentException("Invalid OpenCode download URL for " + platform);
            String checksum = required(properties, artifact + ".sha512").toLowerCase(Locale.ROOT);
            if (!checksum.matches("[a-f0-9]{128}")) throw new IllegalArgumentException("Invalid OpenCode SHA-512 for " + platform);
            values.put(platform, new Distribution(artifact, url, checksum, platform.startsWith("windows-") ? "opencode.exe" : "opencode"));
        }
        distributions = Map.copyOf(values);
    }
    String version() { return version; }
    Optional<Distribution> distribution(HostPlatform platform) {
        return platform.recognized() ? Optional.ofNullable(distributions.get(platform.id())) : Optional.empty();
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing OpenCode release property: " + key);
        return value.strip();
    }
}
