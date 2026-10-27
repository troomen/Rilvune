package dev.editor.platform.text.ui.syntax;

import dev.editor.platform.text.domain.syntax.SyntaxKind;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.SyntaxColors;
import dev.editor.platform.theme.TextHighlightColors;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SyntaxColorsTest {
    @Test void semanticForegroundsRemainReadableOnSourceSearchAndDiffBackgrounds() {
        var theme = EditorTheme.graphite();
        var backgrounds = List.of(theme.surface(), TextHighlightColors.DIFF_ADDED, TextHighlightColors.DIFF_REMOVED,
                TextHighlightColors.DIFF_CHANGED, TextHighlightColors.SEARCH);
        for (var kind : SyntaxKind.values()) for (var background : backgrounds) {
            Color foreground = SyntaxColors.color(kind, theme);
            double ratio = (luminance(foreground) + .05) / (luminance(background) + .05);
            assertTrue(ratio >= 4.5, kind + " contrast " + ratio + " on " + Integer.toHexString(background.getRGB()));
            Color overlay = TextHighlightColors.CURRENT_LINE_OVERLAY;
            double caretRatio = (luminance(composite(overlay, foreground)) + .05) / (luminance(composite(overlay, background)) + .05);
            assertTrue(caretRatio >= 4.5, kind + " caret-row contrast " + caretRatio + " on " + Integer.toHexString(background.getRGB()));
            assertSame(foreground, SyntaxColors.color(kind, theme), "Paint must reuse immutable palette colors");
        }
    }
    @Test void islandsReferenceFitsExistingCategoriesWithoutLanguageSpecificOrSemanticOverrides() {
        var theme = EditorTheme.graphite();
        assertEquals(new Color(0xCF8E6D), SyntaxColors.color(SyntaxKind.KEYWORD, theme));
        assertEquals(new Color(0x6AAB73), SyntaxColors.color(SyntaxKind.STRING, theme));
        assertEquals(new Color(0x2AACB8), SyntaxColors.color(SyntaxKind.NUMBER, theme));
        assertEquals(new Color(0x56A8F5), SyntaxColors.color(SyntaxKind.FUNCTION, theme));
        assertEquals(new Color(0xC77DBB), SyntaxColors.color(SyntaxKind.VARIABLE, theme));
        assertEquals(new Color(0xB3AE60), SyntaxColors.color(SyntaxKind.ANNOTATION, theme));
        assertEquals(new Color(0xD5B778), SyntaxColors.color(SyntaxKind.MARKUP, theme));
        assertEquals(new Color(0xFA6675), SyntaxColors.color(SyntaxKind.ERROR, theme));
        assertEquals(new Color(0x92969F), SyntaxColors.color(SyntaxKind.COMMENT, theme));
        assertSame(theme.text(), SyntaxColors.color(SyntaxKind.TYPE, theme));
        assertSame(theme.text(), SyntaxColors.color(SyntaxKind.ATTRIBUTE, theme));
        assertEquals(SyntaxColors.color(SyntaxKind.KEYWORD, theme), SyntaxColors.color(SyntaxKind.LITERAL, theme));
    }
    @Test void nativeSelectionKeepsReadableForegroundDistinctFromQuietSearchMatches() {
        var theme = EditorTheme.graphite();
        assertNotEquals(theme.selection(), TextHighlightColors.SEARCH);
        assertTrue((luminance(theme.text()) + .05) / (luminance(theme.selection()) + .05) >= 4.5);
        var overlay = TextHighlightColors.CURRENT_LINE_OVERLAY;
        assertTrue((luminance(composite(overlay, theme.text())) + .05) / (luminance(composite(overlay, theme.selection())) + .05) >= 4.5);
    }
    private static Color composite(Color overlay, Color background) {
        double a = overlay.getAlpha() / 255.0;
        return new Color((int) Math.round(a * overlay.getRed() + (1 - a) * background.getRed()),
                (int) Math.round(a * overlay.getGreen() + (1 - a) * background.getGreen()),
                (int) Math.round(a * overlay.getBlue() + (1 - a) * background.getBlue()));
    }
    private static double luminance(Color color) { return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue()); }
    private static double linear(int channel) { double value = channel / 255.0; return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4); }
}
