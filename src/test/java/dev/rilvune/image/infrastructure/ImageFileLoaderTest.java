package dev.rilvune.image.infrastructure;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.CancellationException;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ImageFileLoaderTest {
    @TempDir Path directory;
    private final ImageMemoryBudget budget = new ImageMemoryBudget(128L << 20);
    private ImageFileLoader loader() { return new ImageFileLoader(directory, new ImageFileLoader.Limits(1 << 20, 100, 10000), budget); }

    @Test void allBundledFormatsDecodeWithOwnedNormalizedPixelsAndReleaseExactlyOnce() throws Exception {
        for (String format : new String[]{"png", "jpeg", "bmp", "gif", "tiff", "wbmp"}) {
            var pixels = new BufferedImage(17, 13, format.equals("wbmp") ? BufferedImage.TYPE_BYTE_BINARY : BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(4, 5, 0xFFFFFFFF);
            Path file = directory.resolve("sample." + format);
            assertTrue(ImageIO.write(pixels, format, file.toFile()), format + " writer is bundled");
            assertTrue(ImageFileLoader.isImage(file));
            var image = loader().load(file, () -> false);
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.pixels().getType());
            assertEquals(17, image.pixels().getWidth()); assertEquals(13, image.pixels().getHeight());
            assertEquals(17 * 13 * 4, budget.used());
            image.close(); image.close(); assertEquals(0, budget.used(), format);
        }
    }
    @Test void signatureWinsOverExtensionAndTransparencyIsPreserved() throws Exception {
        var pixels = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, 0x804080C0);
        Path file = directory.resolve("misleading.txt"); ImageIO.write(pixels, "png", file.toFile());
        assertTrue(ImageFileLoader.isImage(file));
        try (var image = loader().load(file, () -> false)) {
            assertEquals(0x804080C0, image.pixels().getRGB(0, 0)); assertEquals(0, image.pixels().getRGB(1, 1));
        }
    }
    @Test void ordinaryTextAndSvgStayTextButCorruptImageSuffixStaysAnImageError() throws Exception {
        assertFalse(ImageFileLoader.isImage(Files.writeString(directory.resolve("notes.txt"), "hello")));
        assertFalse(ImageFileLoader.isImage(Files.writeString(directory.resolve("icon.svg"), "<svg/>")));
        Path bad = Files.writeString(directory.resolve("bad.PNG"), "not image bytes");
        assertTrue(ImageFileLoader.isImage(bad));
        assertThrows(java.io.IOException.class, () -> loader().load(bad, () -> false)); assertEquals(0, budget.used());
        assertTrue(ImageFileLoader.isImage(Files.writeString(directory.resolve("photo.webp"), "bad")));
    }
    @Test void bytePixelAndMemoryLimitsFailBeforePublishingAndReleaseReservations() throws Exception {
        Path file = directory.resolve("image.png"); ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        assertThrows(java.io.IOException.class, () -> new ImageFileLoader(directory, new ImageFileLoader.Limits(10, 100, 10000), budget).load(file, () -> false));
        assertThrows(java.io.IOException.class, () -> new ImageFileLoader(directory, new ImageFileLoader.Limits(1 << 20, 100, 20), budget).load(file, () -> false));
        assertEquals(0, budget.used());
        var tiny = new ImageMemoryBudget(32);
        assertThrows(java.io.IOException.class, () -> new ImageFileLoader(directory, ImageFileLoader.Limits.standard(), tiny).load(file, () -> false));
        assertEquals(0, tiny.used());
    }
    @Test void truncatedInputAndCancellationNeverRetainBytes() throws Exception {
        Path file = directory.resolve("image.png"); ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        byte[] bytes = Files.readAllBytes(file); Files.write(file, java.util.Arrays.copyOf(bytes, 40));
        assertThrows(java.io.IOException.class, () -> loader().load(file, () -> false)); assertEquals(0, budget.used());
        Files.write(file, bytes);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(CancellationException.class, () -> loader().load(file, () -> calls.incrementAndGet() > 3));
        assertEquals(0, budget.used());
    }
    @Test void outsideBoundaryAndSymlinkEscapeAreRejected() throws Exception {
        Path allowed = Files.createDirectory(directory.resolve("allowed")); Path outside = directory.resolve("outside.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", outside.toFile());
        Path alias = Files.createSymbolicLink(allowed.resolve("escape.png"), outside);
        assertThrows(java.io.IOException.class, () -> new ImageFileLoader(allowed, ImageFileLoader.Limits.standard(), budget).load(alias, () -> false));
        assertEquals(0, budget.used());
    }
    @Test void gifFirstFrameUsesLogicalCanvasAndOffset() throws Exception {
        Path path = directory.resolve("offset.gif"); var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var pixels = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) pixels.setRGB(x, y, 0xFFFF0000);
        try (var output = new javax.imageio.stream.FileImageOutputStream(path.toFile())) {
            writer.setOutput(output); var param = writer.getDefaultWriteParam();
            var stream = writer.getDefaultStreamMetadata(param);
            var tree = (IIOMetadataNode) stream.getAsTree("javax_imageio_gif_stream_1.0");
            var screen = (IIOMetadataNode) tree.getElementsByTagName("LogicalScreenDescriptor").item(0);
            screen.setAttribute("logicalScreenWidth", "20"); screen.setAttribute("logicalScreenHeight", "10"); stream.setFromTree("javax_imageio_gif_stream_1.0", tree);
            var frame = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(pixels), param);
            var imageTree = (IIOMetadataNode) frame.getAsTree("javax_imageio_gif_image_1.0");
            var rectangle = (IIOMetadataNode) imageTree.getElementsByTagName("ImageDescriptor").item(0);
            rectangle.setAttribute("imageLeftPosition", "3"); rectangle.setAttribute("imageTopPosition", "2"); frame.setFromTree("javax_imageio_gif_image_1.0", imageTree);
            writer.write(stream, new IIOImage(pixels, null, frame), param);
        } finally { writer.dispose(); }
        try (var image = loader().load(path, () -> false)) {
            assertEquals(20, image.pixels().getWidth()); assertEquals(10, image.pixels().getHeight());
            assertEquals(0xFFFF0000, image.pixels().getRGB(3, 2));
        }
    }
    @Test void multiPageTiffOnlyDecodesFirstPage() throws Exception {
        Path path = directory.resolve("pages.tiff"); var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (var output = new javax.imageio.stream.FileImageOutputStream(path.toFile())) {
            writer.setOutput(output); writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(new BufferedImage(7, 5, BufferedImage.TYPE_USHORT_GRAY), null, null), null);
            writer.writeToSequence(new IIOImage(new BufferedImage(50, 40, BufferedImage.TYPE_INT_RGB), null, null), null);
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        try (var image = loader().load(path, () -> false)) { assertEquals(7, image.pixels().getWidth()); assertEquals(5, image.pixels().getHeight()); }
        assertEquals(0, budget.used());
    }
}
