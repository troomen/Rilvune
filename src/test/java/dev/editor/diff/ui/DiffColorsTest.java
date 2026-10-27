package dev.editor.diff.ui;

import dev.editor.diff.domain.DiffKind;
import dev.editor.platform.theme.*;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiffColorsTest {
    @Test void actualDiffPaintersUseTheSharedLowIntensityBackdropsAndConsistentMarkers() {
        assertSame(TextHighlightColors.DIFF_ADDED, DiffColors.fill(DiffKind.ADDED));
        assertSame(TextHighlightColors.DIFF_REMOVED, DiffColors.fill(DiffKind.REMOVED));
        assertSame(TextHighlightColors.DIFF_CHANGED, DiffColors.fill(DiffKind.CHANGED));
        assertSame(GutterColors.ADDED, DiffColors.edge(DiffKind.ADDED));
        assertSame(GutterColors.MODIFIED, DiffColors.edge(DiffKind.CHANGED));
        assertNotEquals(DiffColors.fill(DiffKind.ADDED), TextHighlightColors.SEARCH);
    }
    @Test void changeEdgesAndSourceMarkersRemainVisibleWithoutBrightTextBackgrounds() {
        var theme = EditorTheme.graphite();
        for (var kind : DiffKind.values()) {
            assertTrue(contrast(DiffColors.edge(kind), DiffColors.fill(kind)) >= 3, kind.toString());
            assertTrue(contrast(DiffColors.edge(kind), theme.surface()) >= 3, kind.toString());
            assertSame(DiffColors.edge(kind), DiffColors.edge(kind)); assertSame(DiffColors.fill(kind), DiffColors.fill(kind));
        }
        for (var marker : List.of(GutterColors.ADDED, GutterColors.MODIFIED, GutterColors.DELETED)) assertTrue(contrast(marker, theme.surface()) >= 3);
    }
    private static double contrast(Color a, Color b) { return (Math.max(luminance(a), luminance(b)) + .05) / (Math.min(luminance(a), luminance(b)) + .05); }
    private static double luminance(Color color) { return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue()); }
    private static double linear(int channel) { double value = channel / 255.0; return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4); }
}
