package dev.rilvune.workspace.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Read-only filesystem boundary. Relative paths are resolved from the workspace root. */
public interface WorkspaceFiles {
    Workspace openWorkspace(Path root) throws IOException;

    /** Lists immediate children only, with directories first and deterministic name order. */
    List<WorkspaceEntry> list(Workspace workspace, Path directory) throws IOException;

    /** Reads a bounded, strictly decoded UTF-8 text preview within the workspace. */
    TextFile read(Workspace workspace, Path file) throws IOException;

    /** Resolve an existing regular file within this root; implementations may avoid reading its payload. */
    default Path canonicalFile(Workspace workspace, Path file) throws IOException { return read(workspace, file).path(); }
}
