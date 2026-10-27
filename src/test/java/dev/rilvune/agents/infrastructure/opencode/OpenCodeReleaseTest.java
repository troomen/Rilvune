package dev.rilvune.agents.infrastructure.opencode;

import dev.rilvune.platform.runtime.HostPlatform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeReleaseTest {
    @ParameterizedTest @CsvSource({
            "Linux, amd64, linux-x64-baseline, opencode", "Linux, arm64, linux-arm64, opencode",
            "Mac OS X, x86_64, darwin-x64-baseline, opencode", "Mac OS X, amd64, darwin-x64-baseline, opencode",
            "Darwin, arm64, darwin-arm64, opencode", "Windows 11, amd64, windows-x64-baseline, opencode.exe",
            "Windows 11, aarch64, windows-arm64, opencode.exe"
    })
    void bundledManifestSelectsPinnedArtifactsAndPreservesExistingCachePaths(String os, String arch, String artifact, String executable) {
        var release = OpenCodeRelease.bundled();
        var distribution = release.distribution(HostPlatform.detect(os, arch)).orElseThrow();
        assertEquals(artifact, distribution.directory()); assertEquals(executable, distribution.executableName());
        assertEquals("https", distribution.url().getScheme());
        assertEquals("registry.npmjs.org", distribution.url().getHost());
        assertTrue(distribution.url().getPath().contains("cli-" + artifact + "-" + release.version()));
        assertTrue(distribution.sha512().matches("[a-f0-9]{128}"));
    }
    @Test void recognizedPlatformsWithoutAnArtifactRemainUnsupported() {
        Properties properties = new Properties(); properties.setProperty("version", "2.1.3");
        var release = new OpenCodeRelease(properties);
        assertTrue(release.distribution(HostPlatform.detect("Mac OS X", "amd64")).isEmpty());
        assertTrue(OpenCodeRelease.bundled().distribution(HostPlatform.detect("Mac OS X", "i386")).isEmpty());
    }
    @Test void malformedPinnedArtifactsFailBeforeInstallation() {
        Properties properties = new Properties(); properties.setProperty("version", "2.1.3");
        properties.setProperty("platform.macos-x86_64", "../escape");
        assertThrows(IllegalArgumentException.class, () -> new OpenCodeRelease(properties));
        properties.setProperty("platform.macos-x86_64", "darwin-x64-baseline");
        properties.setProperty("darwin-x64-baseline.url", "https://registry.npmjs.org/fixture.tgz");
        properties.setProperty("darwin-x64-baseline.sha512", "not-a-checksum");
        assertThrows(IllegalArgumentException.class, () -> new OpenCodeRelease(properties));
    }
}
