package dev.editor.platform.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.editor.settings.domain.EditorSettings;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Visual tokens are owned by the UI, never by the application model. */
public record EditorTheme(
        Color chrome, Color surface, Color raised, Color border,
        Color text, Color muted, Color accent, Color selection,
        Font interfaceFont, Font codeFont) {

    public static EditorTheme graphite() {
        return from(EditorSettings.defaults());
    }

    public static EditorTheme from(EditorSettings settings) {
        FontCatalog fonts = new FontCatalog();
        return new EditorTheme(
                new Color(0x25272A), new Color(0x191B1D), new Color(0x222427),
                new Color(0x393C41), new Color(0xBCBEC4), new Color(0x747982),
                new Color(0x4B83F5), new Color(0x2D4570),
                fonts.font(settings.uiFontFamily(), Font.PLAIN, settings.uiFontSize()),
                fonts.font(settings.editorFontFamily(), Font.PLAIN, settings.editorFontSize()));
    }

    public void install() {
        if (!(UIManager.getLookAndFeel() instanceof FlatDarkLaf) && !FlatDarkLaf.setup()) {
            throw new IllegalStateException("Cannot initialize FlatLaf");
        }
        UiTypography.installDefaults(interfaceFont);
        UIManager.put("Panel.background", surface);
        UIManager.put("Label.foreground", text);
        UIManager.put("Button.background", raised);
        UIManager.put("Button.foreground", text);
        UIManager.put("Component.accentColor", accent);
        UIManager.put("Component.focusColor", accent);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("ScrollBar.track", surface);
        UIManager.put("ScrollBar.thumb", border);
        UIManager.put("Tree.background", surface);
        UIManager.put("Tree.foreground", text);
        UIManager.put("Tree.selectionBackground", selection);
        UIManager.put("Tree.selectionForeground", text);
        UIManager.put("Tree.selectionInactiveBackground", selection);
        UIManager.put("Tree.rowHeight", Math.max(26, interfaceFont.getSize() + 12));
        UIManager.put("Tree.selectionArc", 6);
        UIManager.put("Tree.showDefaultIcons", false);
        UIManager.put("TabbedPane.background", surface);
        UIManager.put("TabbedPane.contentAreaColor", surface);
        UIManager.put("TabbedPane.underlineColor", accent);
        UIManager.put("TabbedPane.selectedBackground", raised);
        UIManager.put("TabbedPane.tabHeight", Math.max(38, interfaceFont.getSize() + 22));
        UIManager.put("SplitPane.background", chrome);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("TextArea.background", surface);
        UIManager.put("TextArea.foreground", text);
        UIManager.put("TextArea.selectionBackground", selection);
        UIManager.put("TextArea.caretForeground", text);
        UIManager.put("TextField.background", raised);
        UIManager.put("TextField.foreground", text);
        UIManager.put("TextField.caretForeground", text);
        UIManager.put("TextField.selectionBackground", selection);
        UIManager.put("FormattedTextField.background", raised);
        UIManager.put("FormattedTextField.foreground", text);
        UIManager.put("ComboBox.background", raised);
        UIManager.put("ComboBox.foreground", text);
        UIManager.put("ComboBox.buttonBackground", raised);
        UIManager.put("ComboBox.selectionBackground", selection);
        UIManager.put("Spinner.background", raised);
        UIManager.put("Spinner.foreground", text);
        UIManager.put("List.background", surface);
        UIManager.put("List.foreground", text);
        UIManager.put("List.selectionBackground", selection);
        UIManager.put("List.selectionForeground", text);
        UIManager.put("ScrollPane.background", surface);
        UIManager.put("Viewport.background", surface);
        UIManager.put("MenuBar.background", chrome);
        UIManager.put("PopupMenu.background", raised);
        UIManager.put("ToolTip.background", raised);
        UIManager.put("ToolTip.foreground", text);
    }
}
