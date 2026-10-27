package dev.rilvune.agents.ui;

import dev.rilvune.platform.theme.*;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;

/** Selectable, wrapping message text. Streaming replaces only the changed suffix of its document. */
final class AgentTextView extends JTextPane {
    private String source = "", displayed = "";
    private boolean formatted;
    private EditorTheme theme;

    AgentTextView(EditorTheme theme) {
        setEditorKit(new WrappingEditorKit()); setEditable(false); setOpaque(false); setBorder(null); setMargin(new Insets(3, 0, 6, 0));
        ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        applyTheme(theme);
    }
    void showText(String source, boolean formatted) {
        if (this.source.equals(source) && this.formatted == formatted) return;
        this.source = source; this.formatted = formatted; updateText();
    }
    void applyTheme(EditorTheme theme) { this.theme = theme; UiTypography.apply(this, theme); updateText(); }
    private void updateText() {
        List<AgentTextFormat.Span> spans = formatted ? AgentTextFormat.parse(source)
                : List.of(new AgentTextFormat.Span(source, AgentTextFormat.Style.TEXT));
        StringBuilder joined = new StringBuilder(); spans.forEach(span -> joined.append(span.text())); String next = joined.toString();
        int prefix = 0;
        while (prefix < Math.min(displayed.length(), next.length()) && displayed.charAt(prefix) == next.charAt(prefix)) prefix++;
        if (prefix > 0 && prefix < next.length() && Character.isLowSurrogate(next.charAt(prefix))) prefix--;
        int dot = getCaret().getDot(), mark = getCaret().getMark();
        StyledDocument document = getStyledDocument();
        try {
            document.remove(prefix, document.getLength() - prefix);
            document.insertString(prefix, next.substring(prefix), null);
            int offset = 0;
            for (var span : spans) {
                SimpleAttributeSet attributes = new SimpleAttributeSet();
                Font font = span.style() == AgentTextFormat.Style.CODE ? theme.codeFont() : theme.interfaceFont();
                StyleConstants.setFontFamily(attributes, font.getFamily()); StyleConstants.setFontSize(attributes, font.getSize());
                StyleConstants.setBold(attributes, span.style() == AgentTextFormat.Style.BOLD || span.style() == AgentTextFormat.Style.HEADING);
                StyleConstants.setForeground(attributes, span.style() == AgentTextFormat.Style.MUTED ? theme.muted() : theme.text());
                if (span.style() == AgentTextFormat.Style.CODE) StyleConstants.setBackground(attributes, theme.raised());
                document.setCharacterAttributes(offset, span.text().length(), attributes, true); offset += span.text().length();
            }
            setCaretPosition(Math.min(mark, document.getLength())); moveCaretPosition(Math.min(dot, document.getLength()));
            displayed = next;
        } catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public Dimension getPreferredSize() {
        // Width comes from the real layout/viewport, never from resizing a component while its parent measures it.
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(1, getWidth()), Math.max(getFontMetrics(getFont()).getHeight() + 9, size.height));
    }
    /** Long paths/JSON tokens wrap too; a nested viewport must never shrink the view to zero width. */
    private static final class WrappingEditorKit extends StyledEditorKit {
        @Override public ViewFactory getViewFactory() { return element -> switch (element.getName()) {
            case AbstractDocument.ContentElementName -> new LabelView(element) { @Override public float getMinimumSpan(int axis) { return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis); } };
            case AbstractDocument.ParagraphElementName -> new ParagraphView(element);
            case AbstractDocument.SectionElementName -> new BoxView(element, View.Y_AXIS);
            case StyleConstants.ComponentElementName -> new ComponentView(element);
            case StyleConstants.IconElementName -> new IconView(element);
            default -> new LabelView(element);
        }; }
    }
}
