package dev.rilvune.platform.desktop;

import dev.rilvune.platform.runtime.HostPlatform;
import org.junit.jupiter.api.Test;
import javax.swing.KeyStroke;
import static org.junit.jupiter.api.Assertions.*;

class DesktopShortcutsTest {
    @Test void appleSiliconUsesCommandForApplicationAndEditorActions() {
        var mac = HostPlatform.detect("Mac OS X", "aarch64");
        for (String suffix : new String[]{"F", "shift F", "Z", "shift Z", "ENTER", "W", "alt RIGHT"})
            assertEquals(KeyStroke.getKeyStroke("meta " + suffix), DesktopShortcuts.keyStroke("ctrl " + suffix, mac));
        assertEquals(KeyStroke.getKeyStroke("ctrl TAB"), DesktopShortcuts.keyStroke("ctrl TAB", mac));
        assertEquals(KeyStroke.getKeyStroke("alt F4"), DesktopShortcuts.keyStroke("alt F4", mac));
    }

    @Test void linuxAndUnknownDesktopsRetainControlShortcuts() {
        for (String os : new String[]{"Linux", "FreeBSD", "Windows 11"})
            assertEquals(KeyStroke.getKeyStroke("ctrl shift F"),
                    DesktopShortcuts.keyStroke("ctrl shift F", HostPlatform.detect(os, "amd64")));
    }
}
