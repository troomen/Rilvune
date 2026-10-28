package dev.rilvune.platform.desktop;

import dev.rilvune.platform.runtime.HostPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SystemBrowserTest {
    private static final URI LINK = URI.create("https://example.com/sign-in");

    @Test void linuxPortalTakesPrecedenceAndFallsBackWhenUnavailable() throws Exception {
        for (boolean available : List.of(true, false)) {
            var calls = new ArrayList<String>();
            var opener = SystemBrowser.select(HostPlatform.detect("Linux", "amd64"), true,
                    uri -> { assertEquals(LINK, uri); calls.add("portal"); return available; },
                    uri -> { assertEquals(LINK, uri); calls.add("desktop"); return true; });
            SystemBrowser.browse(LINK, opener);
            assertEquals(available ? List.of("portal") : List.of("portal", "desktop"), calls);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Mac OS X", "Darwin", "Windows 11", "FreeBSD"})
    void otherOperatingSystemsNeverUseTheLinuxPortal(String os) throws Exception {
        var opener = SystemBrowser.select(HostPlatform.detect(os, "x86_64"), true,
                uri -> { fail("Linux portal invoked on " + os); return false; }, uri -> true);
        SystemBrowser.browse(LINK, opener);
    }

    @Test void linuxWithoutSessionBusUsesTheDesktop() throws Exception {
        var opener = SystemBrowser.select(HostPlatform.detect("Linux", "arm64"), false,
                uri -> { fail("No session bus"); return false; }, uri -> true);
        SystemBrowser.browse(LINK, opener);
    }

    @Test void invalidLinksNeverReachPlatformCode() {
        for (URI uri : new URI[]{null, URI.create("file:///etc/passwd"), URI.create("https://user@example.com"), URI.create("relative")}) {
            assertThrows(IllegalArgumentException.class, () -> SystemBrowser.browse(uri, ignored -> { fail("Invalid link"); return true; }));
        }
    }

    @Test void unavailabilityAndInterruptionAreReported() {
        assertTrue(assertThrows(IOException.class, () -> SystemBrowser.browse(LINK, uri -> false)).getMessage().contains("No system browser"));
        assertThrows(InterruptedException.class, () -> SystemBrowser.browse(LINK, uri -> { throw new InterruptedException(); }));
    }
}
