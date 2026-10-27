package dev.rilvune.platform.text.ui.syntax;

import dev.rilvune.platform.text.domain.syntax.*;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.SyntaxColors;
import org.junit.jupiter.api.Test;

import javax.swing.text.PlainDocument;
import javax.swing.text.Segment;
import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxForegroundTest {
    @Test void customGlyphDrawingAntialiasesTokenAndDefaultRunsWithoutChangingCallerGraphics() throws Exception {
        var theme = EditorTheme.graphite();
        String source = "Syntax";
        var document = new PlainDocument(); document.insertString(0, source, null);
        for (boolean token : new boolean[]{false, true}) {
            var input = new SyntaxInput(new SyntaxLanguageId("fixture"), source);
            var builder = SyntaxResult.builder(input, new SyntaxWorkBudget(SyntaxLimits.interactive(), () -> false, () -> 0));
            if (token) builder.add(0, source.length(), SyntaxKind.KEYWORD);
            var result = builder.build();
            var foreground = new SyntaxForeground(() -> result, () -> theme);
            var image = new BufferedImage(160, 50, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(theme.surface()); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 23)); graphics.setColor(Color.MAGENTA);
                // A component's Swing AA client property does not configure a custom painter's Graphics.
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                var transform = graphics.getTransform(); var font = graphics.getFont();
                float end = foreground.draw(graphics, 5, 32, 0, source.length(), document, new Segment(), null, theme.text());
                assertTrue(end > 5);
                assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, graphics.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
                assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF, graphics.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS));
                assertEquals(Color.MAGENTA, graphics.getColor()); assertEquals(font, graphics.getFont()); assertEquals(transform, graphics.getTransform());
            } finally { graphics.dispose(); }
            int background = theme.surface().getRGB();
            int ink = (token ? SyntaxColors.color(SyntaxKind.KEYWORD, theme) : theme.text()).getRGB();
            int solid = 0, blended = 0;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                if (pixel == ink) solid++;
                else if (pixel != background) blended++;
            }
            assertTrue(solid > 0, "No glyph foreground was painted");
            assertTrue(blended > 0, "No antialiased glyph edges in " + (token ? "token" : "default") + " foreground");
        }
    }
}
