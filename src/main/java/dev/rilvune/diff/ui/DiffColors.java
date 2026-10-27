package dev.rilvune.diff.ui;

import dev.rilvune.diff.domain.DiffKind;
import dev.rilvune.platform.theme.GutterColors;
import dev.rilvune.platform.theme.TextHighlightColors;

import java.awt.Color;

final class DiffColors {
    private DiffColors() { }
    static Color fill(DiffKind kind) {
        return switch (kind) {
            case ADDED -> TextHighlightColors.DIFF_ADDED;
            case REMOVED -> TextHighlightColors.DIFF_REMOVED;
            case CHANGED -> TextHighlightColors.DIFF_CHANGED;
        };
    }
    static Color edge(DiffKind kind) {
        return switch (kind) {
            case ADDED -> GutterColors.ADDED;
            case REMOVED -> TextHighlightColors.DIFF_REMOVED_EDGE;
            case CHANGED -> GutterColors.MODIFIED;
        };
    }
}
