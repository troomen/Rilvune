package dev.editor.platform.text.ui.syntax;

import javax.swing.text.*;
import java.awt.Graphics2D;

/** Same public Swing drawing hooks in ordinary and source-preserving decorated views. */
public final class SyntaxViews {
    private SyntaxViews() { }
    public static View create(Element element, boolean wrap, boolean words, SyntaxForeground foreground) {
        return wrap ? new Wrapped(element, words, foreground) : new Plain(element, foreground);
    }
    private static final class Plain extends PlainView {
        private final SyntaxForeground foreground;
        Plain(Element element, SyntaxForeground foreground) { super(element); this.foreground = foreground; }
        @Override protected float drawUnselectedText(Graphics2D graphics, float x, float y, int from, int to) throws BadLocationException {
            if (foreground == null || !getContainer().isEnabled() || !foreground.available(getDocument()))
                return super.drawUnselectedText(graphics, x, y, from, to);
            return foreground.draw(graphics, x, y, from, to, getDocument(), getLineBuffer(), this, getContainer().getForeground());
        }
    }
    private static final class Wrapped extends WrappedPlainView {
        private final SyntaxForeground foreground;
        Wrapped(Element element, boolean words, SyntaxForeground foreground) { super(element, words); this.foreground = foreground; }
        @Override protected float drawUnselectedText(Graphics2D graphics, float x, float y, int from, int to) throws BadLocationException {
            if (foreground == null || !getContainer().isEnabled() || !foreground.available(getDocument()))
                return super.drawUnselectedText(graphics, x, y, from, to);
            return foreground.draw(graphics, x, y, from, to, getDocument(), getLineBuffer(), this, getContainer().getForeground());
        }
    }
}
