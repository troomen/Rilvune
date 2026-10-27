package dev.editor.image.infrastructure;

import java.io.IOException;

/** Charges retained pixels and admitted transient allocations until their real owner exits. */
public final class ImageMemoryBudget {
    private final long limit;
    private long used;
    public ImageMemoryBudget(long limit) { if (limit < 1) throw new IllegalArgumentException("Positive image budget required"); this.limit = limit; }
    public static ImageMemoryBudget standard() { return new ImageMemoryBudget(Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 4)); }
    public synchronized long used() { return used; }
    public synchronized Reservation reserve(long bytes) throws IOException {
        if (bytes < 0 || bytes > limit - used) throw new IOException("Image preview memory limit reached. Close other image tabs and retry.");
        used += bytes; return new Reservation(bytes);
    }
    public final class Reservation implements AutoCloseable {
        private long bytes;
        private boolean closed;
        private Reservation(long bytes) { this.bytes = bytes; }
        public void resize(long next) throws IOException {
            synchronized (ImageMemoryBudget.this) {
                if (closed) throw new IllegalStateException("Reservation already released");
                if (next < 0 || next > limit - used + bytes) throw new IOException("Image preview memory limit reached. Close other image tabs and retry.");
                used += next - bytes; bytes = next;
            }
        }
        @Override public void close() {
            synchronized (ImageMemoryBudget.this) { if (!closed) { closed = true; used -= bytes; bytes = 0; } }
        }
    }
}
