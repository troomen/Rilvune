package dev.rilvune.app;

import dev.rilvune.platform.runtime.HostPlatform;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LaunchDefaultsTest {
    @Test void finderLaunchUsesWritableUserLocationsInsteadOfTheWorkingDirectory() {
        Path home = Path.of("/Users/example with spaces");
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("Mac OS X", "amd64"), Path.of("/"), home);
        var options = LaunchOptions.parse(new String[0], defaults);
        assertNull(options.workspace());
        assertEquals(home, options.browseRoot());
        assertEquals(home.resolve("Library/Application Support/Rilvune"), options.profile());
    }

    @Test void linuxKeepsItsExistingPortableDefaults() {
        Path working = Path.of("working directory").toAbsolutePath();
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("Linux", "arm64"), working, Path.of("/home/example"));
        var options = LaunchOptions.parse(new String[0], defaults);
        assertEquals(working, options.browseRoot());
        assertEquals(working.resolve(".local"), options.profile());
    }

    @Test void explicitPathsAndWorkspaceBrowsingOverridePlatformDefaults() {
        var defaults = LaunchDefaults.forPlatform(HostPlatform.detect("Darwin", "x86_64"), Path.of("/"), Path.of("/Users/example"));
        var explicit = LaunchOptions.parse(new String[]{"--browse-root", "projects", "--profile", "custom profile"}, defaults);
        assertEquals(Path.of("projects").toAbsolutePath(), explicit.browseRoot());
        assertEquals(Path.of("custom profile").toAbsolutePath(), explicit.profile());
        var project = LaunchOptions.parse(new String[]{"--workspace", "/projects/example"}, defaults);
        assertEquals(Path.of("/projects"), project.browseRoot());
        assertEquals(defaults.profile(), project.profile());
    }
}
