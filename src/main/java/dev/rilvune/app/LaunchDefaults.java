package dev.rilvune.app;

import dev.rilvune.platform.runtime.HostPlatform;
import java.nio.file.Path;

/** Application-owned defaults; native bundles must not store user data beside the executable. */
record LaunchDefaults(Path browseRoot, Path profile) {
    static LaunchDefaults current() {
        return forPlatform(HostPlatform.current(), Path.of("."), Path.of(System.getProperty("user.home")));
    }

    static LaunchDefaults forPlatform(HostPlatform platform, Path workingDirectory, Path home) {
        Path working = workingDirectory.toAbsolutePath().normalize(), user = home.toAbsolutePath().normalize();
        return switch (platform.operatingSystem()) {
            case MACOS -> new LaunchDefaults(user, user.resolve("Library/Application Support/Rilvune"));
            default -> new LaunchDefaults(working, working.resolve(".local"));
        };
    }
}
