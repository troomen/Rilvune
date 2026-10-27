package dev.editor.platform.theme;

import java.awt.Color;

/** Source-change lane colors, distinct from filename/status backgrounds. */
public final class GutterColors {
    public static final Color ADDED = new Color(0x549159);
    // A small lift from the reference's #375FAD keeps narrow markers/borders above 3:1 contrast.
    public static final Color MODIFIED = new Color(0x4B75C2);
    public static final Color DELETED = new Color(0x868A91);
    private GutterColors() { }
}
