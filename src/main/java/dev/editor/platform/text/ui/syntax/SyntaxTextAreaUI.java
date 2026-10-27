package dev.editor.platform.text.ui.syntax;

import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.Element;
import javax.swing.text.View;

public final class SyntaxTextAreaUI extends BasicTextAreaUI {
    private final SyntaxForeground foreground;
    public SyntaxTextAreaUI(SyntaxForeground foreground) { this.foreground = foreground; }
    /** Preserve the installed Look and Feel's margins/scaling/style lifecycle, not just its colors. */
    public static BasicTextAreaUI forCurrentLookAndFeel(SyntaxForeground foreground) {
        return UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatLaf ? new Flat(foreground) : new SyntaxTextAreaUI(foreground);
    }
    @Override public View create(Element element) {
        // Let Swing's internationalized ParagraphView preserve bidi/shaping/composed-text layout.
        if (Boolean.TRUE.equals(element.getDocument().getProperty("i18n"))) return super.create(element);
        JTextArea area = (JTextArea) getComponent();
        return SyntaxViews.create(element, area.getLineWrap(), area.getWrapStyleWord(), foreground);
    }
    private static final class Flat extends com.formdev.flatlaf.ui.FlatTextAreaUI {
        private final SyntaxForeground foreground;
        Flat(SyntaxForeground foreground) { this.foreground = foreground; }
        @Override public View create(Element element) {
            if (Boolean.TRUE.equals(element.getDocument().getProperty("i18n"))) return super.create(element);
            JTextArea area = (JTextArea) getComponent();
            return SyntaxViews.create(element, area.getLineWrap(), area.getWrapStyleWord(), foreground);
        }
    }
}
