package dev.editor.platform.text.ui.syntax;

import dev.editor.platform.text.domain.syntax.SyntaxResult;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.SyntaxColors;
import dev.editor.platform.theme.UiTypography;
import javax.swing.text.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared foreground-only painting. Swing remains the sole owner of selection and geometry. */
public final class SyntaxForeground {
    private final Supplier<SyntaxResult> result;
    private final Supplier<EditorTheme> theme;
    public SyntaxForeground(Supplier<SyntaxResult> result, Supplier<EditorTheme> theme) {
        this.result = Objects.requireNonNull(result); this.theme = Objects.requireNonNull(theme);
    }
    public boolean available(Document document) {
        SyntaxResult tokens = result.get();
        return tokens != null && tokens.size() > 0 && tokens.length() == document.getLength()
                && !Boolean.TRUE.equals(document.getProperty("i18n"));
    }
    public float draw(Graphics2D graphics, float x, float y, int from, int to, Document document,
                      Segment buffer, TabExpander tabs, Color ordinary) throws BadLocationException {
        SyntaxResult tokens = result.get();
        Graphics2D painter = (Graphics2D) graphics.create();
        try {
            // The public tabbed-text utility has no component/view context from which to obtain Swing AA properties.
            UiTypography.antialias(painter);
            int end = Math.min(to, document.getLength()), offset = from, index = tokens.firstIntersecting(from);
            while (offset < end) {
                int stop = end; Color color = ordinary;
                if (index < tokens.size()) {
                    if (offset < tokens.start(index)) stop = Math.min(stop, tokens.start(index));
                    else {
                        stop = Math.min(stop, tokens.end(index));
                        color = SyntaxColors.color(tokens.kind(index), theme.get()); index++;
                    }
                }
                document.getText(offset, stop - offset, buffer); painter.setColor(color);
                x = Utilities.drawTabbedText(buffer, x, y, painter, tabs, offset);
                offset = stop;
            }
            return x;
        } finally { painter.dispose(); }
    }
}
