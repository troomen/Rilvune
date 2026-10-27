package dev.rilvune.editor.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/** Ordered, explicitly registered file editors. Recognition runs off the control thread. */
public final class EditorFileOpeners {
    @FunctionalInterface public interface FileRecognition { boolean accepts(Path path) throws IOException; }
    public record FileSelection(Path path, BiFunction<Path, Long, CompletableFuture<Void>> opener) {
        public CompletableFuture<Void> open(long request) { return opener.apply(path, request); }
    }
    private record Entry(FileRecognition recognition, BiFunction<Path, Long, CompletableFuture<Void>> opener) { }
    private final List<Entry> entries = new ArrayList<>();
    public void register(FileRecognition recognition, BiFunction<Path, Long, CompletableFuture<Void>> opener) { entries.add(new Entry(recognition, opener)); }
    public FileSelection select(Path path) throws IOException {
        for (var entry : entries) if (entry.recognition().accepts(path)) return new FileSelection(path, entry.opener());
        throw new IOException("No editor supports this file");
    }
}
