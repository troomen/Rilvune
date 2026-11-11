package dev.rilvune.app;

import dev.rilvune.platform.runtime.HostPlatform;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LaunchDefaultsTest {
    @Test void finderLaunchUsesWritableUserLocationsInsteadOfTheWorkingDirectory() {
        Path home = Path.of("/Users/example with spaces");
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("Mac OS X", "amd64"), home);
        var options = LaunchOptions.parse(new String[0], defaults);
        assertNull(options.workspace());
        assertEquals(home, options.browseRoot());
        assertEquals(home.resolve("Library/Application Support/Rilvune"), options.profile());
    }

    @Test void linuxUsesUserStorageRegardlessOfWorkingDirectory() {
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("Linux", "arm64"), Path.of("/home/example"));
        var options = LaunchOptions.parse(new String[0], defaults);
        assertEquals(Path.of("/home/example"), options.browseRoot());
        assertEquals(Path.of("/home/example/.local/share/rilvune"), options.profile());
    }

    @Test void linuxHonorsAbsoluteXdgStorageAndIgnoresRelativePaths() {
        var platform = HostPlatform.detect("Linux", "amd64");
        Path home = Path.of("/home/example");
        assertEquals(Path.of("/data/user apps/rilvune"), LaunchDefaults.forPlatform(platform, home,
                java.util.Map.of("XDG_DATA_HOME", "/data/user apps")).profile());
        assertEquals(home.resolve(".local/share/rilvune"), LaunchDefaults.forPlatform(platform, home,
                java.util.Map.of("XDG_DATA_HOME", "relative")).profile());
    }

    @Test void unsupportedDesktopsStillHaveWritableApplicationDefaults() {
        Path home = Path.of("/home/example");
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("FreeBSD", "riscv64"), home);
        assertEquals(home, defaults.browseRoot());
        assertEquals(home.resolve(".rilvune"), defaults.profile());
    }

    @Test void explicitPathsAndWorkspaceBrowsingOverridePlatformDefaults() {
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("Darwin", "x86_64"), Path.of("/Users/example"));
        var explicit = LaunchOptions.parse(new String[]{"--browse-root", "projects", "--profile", "custom profile"}, defaults);
        assertEquals(Path.of("projects").toAbsolutePath(), explicit.browseRoot());
        assertEquals(Path.of("custom profile").toAbsolutePath(), explicit.profile());
        var project = LaunchOptions.parse(new String[]{"--workspace", "/projects/example"}, defaults);
        assertEquals(Path.of("/projects"), project.browseRoot());
        assertEquals(defaults.profile(), project.profile());
    }
}
