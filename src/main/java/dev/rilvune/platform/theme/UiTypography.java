package dev.rilvune.platform.theme;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Shared Swing typography. Font roles survive UI rebuilds without losing editor or preview fonts. */
public final class UiTypography {
    private static final String FONT_STYLE = "editor.typography.fontStyle";
    private record FontStyle(boolean editor, int style, float size, boolean absolute) {
        Font resolve(EditorTheme theme) {
            Font base = editor ? theme.codeFont() : theme.interfaceFont();
            return base.deriveFont(style, absolute ? size : Math.max(1, base.getSize2D() + size));
        }
    }

    private UiTypography() { }

    /** Call before creating Swing components; defaults also cover transient menus and renderers. */
    public static void initialize() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
    }

    public static void installDefaults(Font interfaceFont) {
        UIManager.put("defaultFont", interfaceFont);
        UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** Apply at view creation and after updateComponentTreeUI. Does not traverse owned windows. */
    public static void apply(Component root, EditorTheme theme) {
        if (root instanceof JComponent control) {
            antialias(control);
            Object configured = control.getClientProperty(FONT_STYLE);
            if (configured instanceof FontStyle style) control.setFont(style.resolve(theme));
            else if (configured instanceof Font font) control.setFont(font);
            else {
                Font current = control.getFont();
                control.setFont(theme.interfaceFont().deriveFont(current == null ? Font.PLAIN : current.getStyle()));
            }
        }
        if (root instanceof JMenu menu) {
            for (Component item : menu.getMenuComponents()) apply(item, theme);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) apply(child, theme);
        }
    }

    public static void interfaceFont(JComponent control, EditorTheme theme) {
        interfaceFont(control, theme, Font.PLAIN, 0);
    }

    public static void interfaceFont(JComponent control, EditorTheme theme, int style, float sizeOffset) {
        configure(control, theme, new FontStyle(false, style, sizeOffset, false));
    }

    public static void interfaceFontAtSize(JComponent control, EditorTheme theme, int style, float size) {
        configure(control, theme, new FontStyle(false, style, size, true));
    }

    public static void editorFont(JComponent control, EditorTheme theme) {
        configure(control, theme, new FontStyle(true, Font.PLAIN, 0, false));
    }

    /** Explicit draft font, for settings previews; never replaced by the current live theme. */
    public static void previewFont(JComponent control, Font font) {
        control.putClientProperty(FONT_STYLE, font);
        control.setFont(font);
        antialias(control);
    }

    public static void antialias(JComponent control) {
        control.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** Swing client properties do not configure Graphics used by custom painters. */
    public static void antialias(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static void configure(JComponent control, EditorTheme theme, FontStyle style) {
        control.putClientProperty(FONT_STYLE, style);
        control.setFont(style.resolve(theme));
        antialias(control);
    }
}
