package dev.rilvune.image.infrastructure;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import javax.imageio.*;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.spi.*;
import javax.imageio.stream.*;

import org.w3c.dom.Node;

/** Synchronous bounded adapter. All I/O, metadata and decoding belong on a worker. */
public final class ImageFileLoader implements ImageFileDecoder {
    public record Limits(int encodedBytes, int side, long pixels) {
        public Limits { if (encodedBytes < 1 || side < 1 || pixels < 1) throw new IllegalArgumentException("Positive image limits required"); }
        public static Limits standard() { return new Limits(20 << 20, 16384, 16777216); }
    }
    public record FileStamp(long size, java.nio.file.attribute.FileTime modified, Object key) {
        public static FileStamp read(Path path) throws IOException {
            var value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!value.isRegularFile()) throw new IOException("Not a regular image file");
            return new FileStamp(value.size(), value.lastModifiedTime(), value.fileKey());
        }
    }
    /** Exclusive ownership transfers to the view only after successful generation checks. */
    public static final class LoadedImage implements AutoCloseable {
        private final BufferedImage pixels;
        private final ImageMemoryBudget.Reservation reservation;
        private final FileStamp stamp;
        private final String format;
        private boolean closed;
        private LoadedImage(BufferedImage pixels, ImageMemoryBudget.Reservation reservation, FileStamp stamp, String format) {
            this.pixels = pixels; this.reservation = reservation; this.stamp = stamp; this.format = format;
        }
        public BufferedImage pixels() { return pixels; }
        public FileStamp stamp() { return stamp; }
        public String format() { return format; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { pixels.flush(); } finally { reservation.close(); }
        }
    }
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "jpg", "bmp", "gif", "tif", "tiff", "wbmp");
    private final Path browseRoot;
    private final Limits limits;
    private final ImageMemoryBudget budget;
    public ImageFileLoader(Path browseRoot, Limits limits, ImageMemoryBudget budget) { this.browseRoot = browseRoot; this.limits = limits; this.budget = budget; }

    /** Content-led routing; bounded header and installed JDK providers only. */
    public static boolean isImage(Path path) throws IOException {
        byte[] header;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { header = input.readNBytes(64); }
        try (var input = memoryInput(header)) {
            ImageReader reader = reader(input);
            if (reader != null) { reader.dispose(); return true; }
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.'); String extension = dot < 0 ? "" : name.substring(dot + 1);
        if (FORMATS.contains(extension) || Set.of("webp", "avif", "heic", "heif", "ico", "icns").contains(extension)) return true;
        return header.length >= 12 && ((header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P')
                || (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p'
                && (new String(header, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1).matches("avif|avis|heic|heix|mif1"))));
    }
    public LoadedImage load(Path requested, BooleanSupplier canceled) throws IOException {
        checkCanceled(canceled);
        Path path = requested.toRealPath();
        if (!path.startsWith(browseRoot)) throw new IOException("Image is outside BROWSE_ROOT");
        var stamp = FileStamp.read(path);
        if (stamp.size() > limits.encodedBytes()) throw new IOException("Image file exceeds " + limits.encodedBytes() + " bytes");
        var reservation = budget.reserve(2L * (stamp.size() + 1) + (8L << 20));
        BufferedImage decoded = null, normalized = null;
        ImageReader reader = null;
        String decodedFormat;
        long retainedPixels;
        try {
            byte[] bytes = readSnapshot(path, canceled, (int)stamp.size());
            if (!stamp.equals(FileStamp.read(path))) throw new IOException("Image changed while loading. Retry.");
            try (var input = memoryInput(bytes)) {
                reader = reader(input);
                if (reader == null) throw new IOException("Unsupported or invalid image. Supported: PNG, JPEG, BMP, GIF, TIFF, WBMP.");
                reader.setInput(input, false, false);
                reader.addIIOReadProgressListener(new CancelProgress(canceled));
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                checkDimensions(width, height);
                int canvasWidth = width, canvasHeight = height, x = 0, y = 0;
                Color background = new Color(0, true);
                if (format.equals("GIF")) {
                    var stream = reader.getStreamMetadata();
                    Node tree = stream == null ? null : stream.getAsTree("javax_imageio_gif_stream_1.0");
                    Node screen = child(tree, "LogicalScreenDescriptor");
                    if (screen == null) throw new IOException("GIF logical canvas is unavailable");
                    canvasWidth = number(screen, "logicalScreenWidth"); canvasHeight = number(screen, "logicalScreenHeight");
                    checkDimensions(canvasWidth, canvasHeight);
                    Node frame = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
                    Node rectangle = child(frame, "ImageDescriptor");
                    x = number(rectangle, "imageLeftPosition"); y = number(rectangle, "imageTopPosition");
                    if (x < 0 || y < 0 || (long)x + width > canvasWidth || (long)y + height > canvasHeight) throw new IOException("GIF frame is outside its logical canvas");
                    Node control = child(frame, "GraphicControlExtension");
                    boolean transparent = control != null && "TRUE".equalsIgnoreCase(attribute(control, "transparentColorFlag"));
                    Node palette = child(tree, "GlobalColorTable");
                    if (!transparent && palette != null) {
                        int index = number(palette, "backgroundColorIndex");
                        for (Node entry = palette.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                            if ("ColorTableEntry".equals(entry.getNodeName()) && number(entry, "index") == index)
                                background = new Color(number(entry, "red"), number(entry, "green"), number(entry, "blue"));
                        }
                    }
                }
                var type = reader.getRawImageType(0);
                if (type == null) {
                    var types = reader.getImageTypes(0);
                    if (!types.hasNext()) throw new IOException("Unsupported image layout");
                    type = types.next();
                }
                var sizes = type.getSampleModel().getSampleSize();
                long bits = 0;
                if (sizes.length > 4) throw new IOException("Unsupported image channel layout");
                for (int size : sizes) { if (size < 1 || size > 32) throw new IOException("Unsupported image channel depth"); bits += size; }
                long perPixel = Math.max(12, (bits + 7) / 8 + 4);
                long pixels = (long) canvasWidth * canvasHeight;
                reservation.resize(Math.addExact(2L * bytes.length + (8L << 20), Math.multiplyExact(perPixel, pixels)));
                checkCanceled(canceled);
                decoded = reader.read(0);
                checkCanceled(canceled);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw new IOException("Image decoder returned unexpected dimensions");
                normalized = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = normalized.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src); graphics.setColor(background); graphics.fillRect(0, 0, canvasWidth, canvasHeight);
                    graphics.setComposite(background.getAlpha() == 0 ? AlphaComposite.Src : AlphaComposite.SrcOver); graphics.drawImage(decoded, x, y, null);
                } finally { graphics.dispose(); }
                decoded.flush(); decoded = null;
                checkCanceled(canceled);
                if (!stamp.equals(FileStamp.read(path))) throw new IOException("Image changed while loading. Retry.");
                retainedPixels = pixels; decodedFormat = format;
            }
            reader.dispose(); reader = null;
            // No decoder/stream/input cache remains owned before lowering the transient reservation.
            reservation.resize(4L * retainedPixels);
            var result = new LoadedImage(normalized, reservation, stamp, decodedFormat);
            normalized = null; reservation = null;
            return result;
        } catch (ArithmeticException | IllegalArgumentException failure) { throw new IOException("Invalid or unsupported image dimensions/layout", failure); }
        finally {
            try { if (reader != null) reader.dispose(); }
            finally {
                try { if (decoded != null) decoded.flush(); }
                finally {
                    try { if (normalized != null) normalized.flush(); }
                    finally { if (reservation != null) reservation.close(); }
                }
            }
        }
    }
    private byte[] readSnapshot(Path path, BooleanSupplier canceled, int expectedBytes) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[Math.addExact(expectedBytes, 1)]; int total = 0;
            while (total < bytes.length) {
                checkCanceled(canceled);
                int count = input.read(bytes, total, Math.min(8192, bytes.length - total));
                if (count < 0) break;
                total += count;
            }
            if (total > limits.encodedBytes()) throw new IOException("Image file grew beyond the encoded byte limit");
            if (total != expectedBytes) throw new IOException("Image changed while loading. Retry.");
            return Arrays.copyOf(bytes, total);
        }
    }

    private void checkDimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > limits.side() || height > limits.side() || (long) width * height > limits.pixels())
            throw new IOException("Image dimensions exceed preview limits");
    }
    private static MemoryCacheImageInputStream memoryInput(byte[] bytes) { return new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)); }
    private static ImageReader reader(ImageInputStream input) throws IOException {
        var providers = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, true);
        while (providers.hasNext()) {
            var provider = providers.next();
            if (!"java.desktop".equals(provider.getClass().getModule().getName()) || Arrays.stream(provider.getFormatNames()).map(name -> name.toLowerCase(Locale.ROOT)).noneMatch(FORMATS::contains)) continue;
            input.seek(0);
            if (provider.canDecodeInput(input)) { input.seek(0); return provider.createReaderInstance(); }
        }
        return null;
    }
    private static void checkCanceled(BooleanSupplier canceled) { if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Image load canceled"); }
    private static Node child(Node node, String name) { if (node != null) for (Node value = node.getFirstChild(); value != null; value = value.getNextSibling()) if (name.equals(value.getNodeName())) return value; return null; }
    private static String attribute(Node node, String name) throws IOException {
        Node value = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        if (value == null) throw new IOException("Invalid image metadata: " + name); return value.getNodeValue();
    }
    private static int number(Node node, String name) throws IOException { try { return Integer.parseInt(attribute(node, name)); } catch (NumberFormatException failure) { throw new IOException("Invalid image metadata: " + name, failure); } }
    private record CancelProgress(BooleanSupplier canceled) implements IIOReadProgressListener {
        private void check(ImageReader reader) { if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) reader.abort(); }
        public void sequenceStarted(ImageReader reader, int index) { check(reader); }
        public void sequenceComplete(ImageReader reader) { }
        public void imageStarted(ImageReader reader, int index) { check(reader); }
        public void imageProgress(ImageReader reader, float percent) { check(reader); }
        public void imageComplete(ImageReader reader) { check(reader); }
        public void thumbnailStarted(ImageReader reader, int image, int thumbnail) { check(reader); }
        public void thumbnailProgress(ImageReader reader, float percent) { check(reader); }
        public void thumbnailComplete(ImageReader reader) { }
        public void readAborted(ImageReader reader) { }
    }
}
