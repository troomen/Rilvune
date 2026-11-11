package dev.rilvune.app.ui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.awt.image.BaseMultiResolutionImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Bundled application artwork, independent of the working directory or installed layout. */
public final class ApplicationIcon {
    private static final BufferedImage SMALL = load("editor-logo-64.png");
    private static final BufferedImage LARGE = load("editor-logo-256.png");
    private static final Image UI_IMAGE = new BaseMultiResolutionImage(SMALL, LARGE);
    private ApplicationIcon() { }

    public static List<Image> images() { return List.of(SMALL, LARGE); }
    public static Image small() { return UI_IMAGE; }

    public static void installTaskbarIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) return;
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.setIconImage(LARGE);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            // Optional desktop integration must not prevent launching the editor.
            System.getLogger(ApplicationIcon.class.getName()).log(System.Logger.Level.DEBUG, "Taskbar icon is unavailable", unavailable);
        }
    }

    private static BufferedImage load(String name) {
        try (var stream = ApplicationIcon.class.getResourceAsStream("/branding/" + name)) {
            if (stream == null) throw new IOException("Missing application icon: " + name);
            BufferedImage image = ImageIO.read(stream);
            if (image == null) throw new IOException("Invalid application icon: " + name);
            return image;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
