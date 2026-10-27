package dev.editor.platform.text.ui;

import dev.editor.platform.text.domain.syntax.*;
import dev.editor.platform.text.infrastructure.syntax.rsta.RstaSyntaxProvider;
import dev.editor.platform.text.ui.syntax.*;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.SyntaxColors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
class SyntaxCompatibilityTest {
    @Test void nativeForegroundHooksPreserveSharedModelTabsWordWrappingAndDecoratedGeometry() throws Exception {
        String source = "public class Example {\n\tString text = \"Hello café\"; // comment\n\tint count = 42;\n}\n";
        var provider = new RstaSyntaxProvider(); var language = new SyntaxLanguageId("java");
        SyntaxResult tokens;
        try (var lexer = provider.create(language)) {
            var defaults = SyntaxLimits.interactive();
            var limits = new SyntaxLimits(defaults.characters(), defaults.lines(), defaults.lineCharacters(), defaults.spans(),
                    2, 256, 512, defaults.payloadBytes(), Duration.ZERO, Duration.ofSeconds(10));
            tokens = lexer.lex(new SyntaxInput(language, source), new SyntaxWorkBudget(limits, () -> false, System::nanoTime));
        }
        assertTrue(tokens.size() > 5);
        FutureTask<Void> test = new FutureTask<>(() -> {
            EditorTheme theme = EditorTheme.graphite();
            theme.install();
            PlainDocument model = new PlainDocument(); model.insertString(0, source, null);
            for (boolean wrap : List.of(false, true)) for (boolean decorated : List.of(false, true)) {
                JTextArea ordinary = area(model, theme, wrap), colored = area(model, theme, wrap);
                var foreground = new SyntaxForeground(() -> tokens, () -> theme);
                var layout = new TextLineLayout(Map.of(1, 2), List.of(new TextLineLayout.Fold(2, 3)));
                if (decorated) {
                    ordinary.setUI(new LineLayoutTextAreaUI(() -> layout));
                    colored.setUI(new LineLayoutTextAreaUI(() -> layout, foreground));
                } else colored.setUI(SyntaxTextAreaUI.forCurrentLookAndFeel(foreground));
                ordinary.setSize(240, 400); colored.setSize(240, 400);
                BufferedImage image = new BufferedImage(240, 400, BufferedImage.TYPE_INT_RGB);
                // WrappedPlainView records its tab origin during paint. Initialize both native allocations equally.
                Graphics2D nativeGraphics = image.createGraphics(); ordinary.paint(nativeGraphics); nativeGraphics.dispose();
                Graphics2D graphics = image.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                colored.paint(graphics); graphics.dispose();
                assertSame(model, colored.getDocument());
                for (int offset = 0; offset <= source.length(); offset++) {
                    var expected = ordinary.modelToView2D(offset); var actual = colored.modelToView2D(offset);
                    assertEquals(expected, actual, "offset " + offset + " wrap=" + wrap + " decorated=" + decorated);
                }
                int keyword = SyntaxColors.color(SyntaxKind.KEYWORD, theme).getRGB();
                assertTrue(hasColor(image, keyword), "Actual keyword foreground was not painted: wrap=" + wrap + " decorated=" + decorated);
                var start = colored.modelToView2D(0); var end = colored.modelToView2D(6);
                boolean antialiased = false;
                for (int y = (int) start.getY(); y < start.getY() + start.getHeight(); y++)
                    for (int x = (int) start.getX(); x < end.getX(); x++) {
                        int pixel = image.getRGB(x, y);
                        if (pixel != keyword && pixel != theme.surface().getRGB()) antialiased = true;
                    }
                assertTrue(antialiased, "Keyword glyph edges must be antialiased: wrap=" + wrap + " decorated=" + decorated);
                colored.select(0, 6); colored.getCaret().setSelectionVisible(true);
                assertEquals("public", colored.getSelectedText());
                Path output = Path.of("build/ui-snapshots/syntax-compatibility-" + wrap + "-" + decorated + ".png");
                Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
            }
            assertEquals(source, model.getText(0, model.getLength()));
            return null;
        });
        SwingUtilities.invokeAndWait(test); test.get();
    }
    @Test void internationalizedDocumentsRetainSwingsNativeLayout() throws Exception {
        FutureTask<Void> test = new FutureTask<>(() -> {
            var model = new PlainDocument(); model.insertString(0, "Arabic العربية and composed e\u0301 😀", null);
            assertEquals(Boolean.TRUE, model.getProperty("i18n"));
            var theme = EditorTheme.graphite();
            theme.install();
            JTextArea original = area(model, theme, true), colored = area(model, theme, true);
            colored.setUI(SyntaxTextAreaUI.forCurrentLookAndFeel(new SyntaxForeground(() -> null, () -> theme)));
            original.setSize(300, 200); colored.setSize(300, 200);
            for (int offset = 0; offset <= model.getLength(); offset++) assertEquals(original.modelToView2D(offset), colored.modelToView2D(offset));
            return null;
        });
        SwingUtilities.invokeAndWait(test); test.get();
    }
    private static JTextArea area(PlainDocument model, EditorTheme theme, boolean wrap) {
        JTextArea area = new JTextArea(model); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        area.setForeground(theme.text()); area.setBackground(theme.surface()); area.setTabSize(4);
        area.setLineWrap(wrap); area.setWrapStyleWord(true); return area;
    }
    private static boolean hasColor(BufferedImage image, int color) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) == color) return true;
        return false;
    }
}
