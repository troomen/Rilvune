package dev.rilvune.document.domain;

import java.io.IOException;
import java.nio.file.Path;

public interface DocumentStore {
    Path canonicalPath(Path path) throws IOException;
    DiskSnapshot read(Path path) throws IOException;

    /** Null expected means create, never replace. Implementations recheck before committing. */
    DiskSnapshot write(Path path, String text, TextFormat format, DiskSnapshot expected) throws IOException;

    /** A new file, retaining mixed separators from the source when available. Never replaces the target. */
    default DiskSnapshot createCopy(Path path, String text, TextFormat format, DiskSnapshot source) throws IOException {
        return write(path, text, format, null);
    }
}
