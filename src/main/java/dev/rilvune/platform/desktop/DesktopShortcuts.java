package dev.rilvune.platform.desktop;

import dev.rilvune.platform.runtime.HostPlatform;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.Objects;

/** Application shortcuts follow the desktop convention; Control-Tab remains tab navigation. */
public final class DesktopShortcuts {
    private DesktopShortcuts() { }

    public static KeyStroke keyStroke(String shortcut) { return keyStroke(shortcut, HostPlatform.current()); }

    static KeyStroke keyStroke(String shortcut, HostPlatform platform) {
        KeyStroke key = Objects.requireNonNull(KeyStroke.getKeyStroke(shortcut), "Invalid shortcut: " + shortcut);
        if (platform.operatingSystem() == HostPlatform.OperatingSystem.MACOS && key.getKeyCode() != KeyEvent.VK_TAB)
            return KeyStroke.getKeyStroke(shortcut.replace("ctrl ", "meta "));
        return key;
    }

    public static String label(String text) {
        return HostPlatform.current().operatingSystem() == HostPlatform.OperatingSystem.MACOS
                ? text.replace("Ctrl+", "Cmd+") : text;
    }
}
