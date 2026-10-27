package dev.editor.platform.theme;

import dev.editor.platform.decoration.PathDecoration;
import java.awt.Color;

/** IntelliJ/Darcula-style filename colors, with brighter variants on a selected row. */
public final class FileStatusColors {
    private FileStatusColors() { }
    public static Color color(PathDecoration.Tone tone, boolean selected) {
        return new Color(switch (tone) {
            case MODIFIED -> selected ? 0x85B5EA : 0x6897BB;
            case UNTRACKED -> selected ? 0xE3A38D : 0xC18372;
            case ADDED -> selected ? 0x91C483 : 0x77A768;
            case CONFLICT -> selected ? 0xF09898 : 0xE07070;
            case IGNORED -> selected ? 0xA0A4AC : 0x747982;
        });
    }
}
