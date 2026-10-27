package dev.rilvune.platform.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class HostPlatformTest {
    @ParameterizedTest @CsvSource({
            "Mac OS X, x86_64, macos-x86_64", "macOS, amd64, macos-x86_64", "Darwin, x64, macos-x86_64",
            "Mac OS X, x86-64, macos-x86_64", "Mac OS X, aarch64, macos-arm64", "macOS, arm64, macos-arm64",
            "Linux, amd64, linux-x86_64", "Linux, aarch64, linux-arm64",
            "Windows 11, AMD64, windows-x86_64", "Windows 10, ARM64, windows-arm64"
    })
    void recognizesJvmNamesAndAliases(String os, String arch, String id) {
        var platform = HostPlatform.detect(os, arch);
        assertTrue(platform.recognized()); assertEquals(id, platform.id());
        assertTrue(platform.description().contains("os.name=" + os));
        assertTrue(platform.description().contains("os.arch=" + arch));
    }
    @ParameterizedTest @CsvSource({"Linux, x86", "Mac OS X, i386", "Linux, riscv64", "FreeBSD, amd64", "NotmacOS, x86_64"})
    void unknownPlatformsNeverFallBackToAnotherArchitecture(String os, String arch) {
        var platform = HostPlatform.detect(os, arch);
        assertFalse(platform.recognized());
        assertTrue(platform.description().contains(os)); assertTrue(platform.description().contains(arch));
    }
    @Test void missingAndUncleanSystemPropertiesHaveSafeDiagnostics() {
        assertFalse(HostPlatform.detect(null, null).recognized());
        assertEquals("macos-x86_64", HostPlatform.detect(" MAC OS X ", " AMD64 ").id());
        assertFalse(HostPlatform.detect("unknown\nOS", "cpu\r").description().contains("\n"));
    }
}
