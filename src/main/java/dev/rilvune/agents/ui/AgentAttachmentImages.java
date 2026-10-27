package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.AgentAttachment;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/** Bounded clipboard encoding and subsampled local previews; always called off the EDT. */
final class AgentAttachmentImages {
    private AgentAttachmentImages() { }
    static byte[] png(Image image) throws IOException {
        int width = image.getWidth(null), height = image.getHeight(null);
        if (width < 1 || height < 1 || (long)width * height > 32_000_000) throw new IOException("Clipboard image is unavailable or exceeds 32 megapixels");
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try {
            Graphics2D graphics = copy.createGraphics();
            try { if (!graphics.drawImage(image, 0, 0, null)) throw new IOException("Clipboard image is not fully available"); }
            finally { graphics.dispose(); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream limited = new FilterOutputStream(bytes) {
                private long size;
                @Override public void write(int value) throws IOException { check(1); out.write(value); }
                @Override public void write(byte[] values, int offset, int length) throws IOException { check(length); out.write(values, offset, length); }
                private void check(int count) throws IOException { size += count; if (size > AgentAttachment.MAX_BYTES) throw new IOException("Clipboard image exceeds 20 MiB"); }
            };
            if (!ImageIO.write(copy, "png", limited)) throw new IOException("PNG encoding is unavailable");
            return bytes.toByteArray();
        } finally { copy.flush(); }
    }
    static BufferedImage thumbnail(Path file, int maxWidth, int maxHeight) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > AgentAttachment.MAX_BYTES) throw new IOException("Attachment is missing or exceeds 20 MiB");
        try (var input = Files.newInputStream(file); var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Preview unavailable for this image format");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true); int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 65536 || height > 65536 || (long)width * height > 100_000_000) throw new IOException("Image dimensions exceed the preview limit");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (int)Math.ceil(Math.max(width / (double)maxWidth, height / (double)maxHeight)));
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                return decoded;
            } finally { reader.dispose(); }
        }
    }
}
