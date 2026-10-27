package dev.editor.document.domain;

import java.io.IOException;
import java.nio.file.Path;

public final class FileChangedException extends IOException {
    public FileChangedException(Path path) { super("File changed again on disk; review its latest version: " + path); }
}
