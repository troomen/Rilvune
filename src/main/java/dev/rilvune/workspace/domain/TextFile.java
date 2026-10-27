package dev.rilvune.workspace.domain;

import java.nio.file.Path;
import java.util.Objects;

/** An immutable text preview, not an editable document or a saved revision. */
public record TextFile(Path path, String text) {
    public TextFile {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(text, "text");
    }
}
