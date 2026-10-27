package dev.rilvune.platform.theme;

import java.awt.Color;

/** Quiet text backdrops coordinated with SyntaxColors; stronger gutter edges carry the change semantics. */
public final class TextHighlightColors {
    public static final Color DIFF_ADDED = new Color(0x1F2B25);
    public static final Color DIFF_REMOVED = new Color(0x342426);
    public static final Color DIFF_CHANGED = new Color(0x22293A);
    public static final Color DIFF_REMOVED_EDGE = new Color(0xDE6A66);
    // Amber distinguishes search results from green additions; the current match uses native blue selection.
    public static final Color SEARCH = new Color(0x2E281B);
    public static final Color CURRENT_LINE_OVERLAY = new Color(130, 150, 180, 11);
    private TextHighlightColors() { }
}
