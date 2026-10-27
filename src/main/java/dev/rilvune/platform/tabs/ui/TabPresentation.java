package dev.rilvune.platform.tabs.ui;

import java.awt.Color;
import java.util.Objects;

/** A tab's display state; it does not imply a filesystem path. */
public record TabPresentation(String title, String tooltip, Color foreground, Color selectedForeground) {
    public TabPresentation(String title, String tooltip, Color foreground) {
        this(title, tooltip, foreground, foreground);
    }
    public TabPresentation {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(tooltip, "tooltip");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(selectedForeground, "selectedForeground");
    }
}
