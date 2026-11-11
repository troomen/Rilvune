package dev.rilvune.app;

import dev.rilvune.platform.runtime.HostPlatform;
import java.nio.file.Path;
import java.util.Map;

/** Application-owned defaults; native bundles must not store user data beside the executable. */
record LaunchDefaults(Path browseRoot, Path profile) {
    static LaunchDefaults current() {
        return forPlatform(HostPlatform.current(), Path.of(System.getProperty("user.home")), System.getenv());
    }

    static LaunchDefaults forPlatform(HostPlatform platform, Path home) {
        return forPlatform(platform, home, Map.of());
    }

    static LaunchDefaults forPlatform(HostPlatform platform, Path home, Map<String, String> environment) {
        Path user = home.toAbsolutePath().normalize();
        return switch (platform.operatingSystem()) {
            case MACOS -> new LaunchDefaults(user, user.resolve("Library/Application Support/Rilvune"));
            case LINUX -> new LaunchDefaults(user, dataHome(environment.get("XDG_DATA_HOME"), user.resolve(".local/share")).resolve("rilvune"));
            case WINDOWS -> new LaunchDefaults(user, dataHome(environment.get("LOCALAPPDATA"), user.resolve("AppData/Local")).resolve("Rilvune"));
            default -> new LaunchDefaults(user, user.resolve(".rilvune"));
        };
    }

    private static Path dataHome(String configured, Path fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        Path path = Path.of(configured);
        return path.isAbsolute() ? path.normalize() : fallback;
    }
}
