package dev.rilvune.app.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationIconTest {
    @Test void windowIconsLoadFromBundledResourcesAtTheirOriginalResolutions() {
        assertEquals(List.of(64, 256), ApplicationIcon.images().stream().map(image -> image.getWidth(null)).toList());
        assertEquals(List.of(64, 256), ApplicationIcon.images().stream().map(image -> image.getHeight(null)).toList());
        assertEquals(64, ApplicationIcon.small().getWidth(null));
        var resolutions = assertInstanceOf(java.awt.image.MultiResolutionImage.class, ApplicationIcon.small());
        assertSame(ApplicationIcon.images().getLast(), resolutions.getResolutionVariant(128, 128));
    }

    @Test void macosIconHasValidLengthsAndContainsTheUnmodifiedPngRepresentations() throws Exception {
        byte[] icon;
        try (var resource = ApplicationIcon.class.getResourceAsStream("/branding/Rilvune.icns")) {
            assertNotNull(resource);
            icon = resource.readAllBytes();
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(icon))) {
            assertEquals("icns", new String(input.readNBytes(4), StandardCharsets.US_ASCII));
            assertEquals(icon.length, input.readInt());
            record Representation(String type, int size) { }
            for (var representation : List.of(new Representation("icp6", 64), new Representation("ic08", 256))) {
                assertEquals(representation.type(), new String(input.readNBytes(4), StandardCharsets.US_ASCII));
                int length = input.readInt();
                assertTrue(length > 8 && length <= icon.length);
                byte[] png = input.readNBytes(length - 8);
                assertEquals(length - 8, png.length);
                int size = representation.size();
                try (var original = ApplicationIcon.class.getResourceAsStream("/branding/editor-logo-" + size + ".png")) {
                    assertNotNull(original);
                    assertArrayEquals(original.readAllBytes(), png);
                }
                var image = ImageIO.read(new ByteArrayInputStream(png));
                assertNotNull(image);
                assertEquals(size, image.getWidth()); assertEquals(size, image.getHeight());
            }
            assertEquals(-1, input.read());
        }
    }
}
