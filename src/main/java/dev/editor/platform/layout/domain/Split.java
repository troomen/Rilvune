package dev.editor.platform.layout.domain;

import java.util.Objects;

/** Fraction of the available space assigned to the first child. */
public record Split(Axis axis, double fraction, LayoutNode first, LayoutNode second) implements LayoutNode {
    public Split {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!Double.isFinite(fraction) || fraction <= 0.0 || fraction >= 1.0) {
            throw new IllegalArgumentException("Split fraction must be finite and between 0 and 1, exclusive");
        }
    }
}
