package dev.editor.agents.infrastructure.opencode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Private OpenCode storage, rooted in the editor profile on every launch platform. */
public final class OpenCodeRuntimePaths {
    private final Path root;

    public OpenCodeRuntimePaths(Path profile) { root = profile.toAbsolutePath().normalize().resolve("opencode"); }
    public Path root() { return root; }
    public Path bin() { return root.resolve("bin"); }
    public Path data() { return root.resolve("data"); }
    public Path config() { return root.resolve("config"); }
    public Path cache() { return root.resolve("cache"); }
    public Path state() { return root.resolve("state"); }

    public Map<String, String> environment() throws IOException {
        Files.createDirectories(root);
        if (Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class))
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        for (Path directory : List.of(data(), config(), cache(), state())) Files.createDirectories(directory);
        // OpenCode appends its namespace to XDG roots; CONFIG_DIR is its supported direct override.
        return Map.of("XDG_DATA_HOME", data().toString(), "XDG_CONFIG_HOME", config().toString(),
                "XDG_CACHE_HOME", cache().toString(), "XDG_STATE_HOME", state().toString(),
                "OPENCODE_CONFIG_DIR", config().toString(), "OPENCODE_DISABLE_AUTOUPDATE", "true");
    }

    static String platform() { return platform(System.getProperty("os.name"), System.getProperty("os.arch")); }
    static String platform(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT), arch = architecture.toLowerCase(Locale.ROOT);
        String system = os.startsWith("windows") ? "windows" : os.contains("mac") || os.contains("darwin") ? "darwin"
                : os.contains("linux") ? "linux" : "unsupported";
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                : arch.equals("amd64") || arch.equals("x86_64") ? "x64-baseline" : "unsupported";
        return system + "-" + cpu;
    }
}
