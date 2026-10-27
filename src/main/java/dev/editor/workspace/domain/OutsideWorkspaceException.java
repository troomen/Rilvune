package dev.editor.workspace.domain;

import java.io.IOException;
import java.nio.file.Path;

public final class OutsideWorkspaceException extends IOException {
    public OutsideWorkspaceException(Path path) { super("Path is outside the workspace: " + path); }
}
