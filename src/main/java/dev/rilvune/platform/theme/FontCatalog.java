package dev.rilvune.platform.theme;

import dev.rilvune.settings.domain.EditorSettings;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Explicit application font registration; nothing is installed into the host operating system. */
public final class FontCatalog {
    private final List<String> families;
    private final List<String> monospacedFamilies;

    public FontCatalog() {
        families = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames(Locale.ROOT))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        monospacedFamilies = families.stream().filter(FontCatalog::isMonospaced).toList();
    }

    public static void registerBundled() throws IOException {
        for (String name : List.of("Hack-Regular.ttf", "Hack-Bold.ttf", "Hack-Italic.ttf", "Hack-BoldItalic.ttf")) {
            try (InputStream resource = FontCatalog.class.getResourceAsStream("/fonts/" + name)) {
                if (resource == null) throw new IOException("Bundled font is missing: " + name);
                Font font = Font.createFont(Font.TRUETYPE_FONT, resource);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            } catch (FontFormatException failure) {
                throw new IOException("Cannot load bundled font " + name, failure);
            }
        }
    }

    public List<String> families() { return families; }
    public List<String> monospacedFamilies() { return monospacedFamilies; }
    public boolean available(String family) { return families.stream().anyMatch(name -> name.equalsIgnoreCase(family)); }

    public Font font(String requestedFamily, int style, int size) {
        return new Font(available(requestedFamily) ? requestedFamily : "Hack", style, size);
    }

    public List<String> missingFamilies(EditorSettings settings) {
        return List.of(settings.uiFontFamily(), settings.editorFontFamily()).stream()
                .distinct().filter(family -> !available(family)).toList();
    }

    private static boolean isMonospaced(String family) {
        var graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            var metrics = graphics.getFontMetrics(new Font(family, Font.PLAIN, 16));
            int width = metrics.charWidth('m');
            return width > 0 && "ilMW09 .".chars().allMatch(character -> metrics.charWidth(character) == width);
        } finally {
            graphics.dispose();
        }
    }
}
