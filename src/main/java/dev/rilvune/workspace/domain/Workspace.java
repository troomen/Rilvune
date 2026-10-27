package dev.rilvune.workspace.domain;

import java.nio.file.Path;
import java.util.Objects;

/** A workspace identity; filesystem validation belongs to {@link WorkspaceFiles}. */
public record Workspace(Path root) {
    public Workspace {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }
}
