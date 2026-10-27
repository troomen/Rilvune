package dev.editor.workspace.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** One activation of a project. Reopening the same directory creates a different context. */
public record ProjectContext(Path root, UUID activation) {
    public ProjectContext {
        root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        Objects.requireNonNull(activation);
    }
    public static ProjectContext activate(Workspace workspace) {
        return new ProjectContext(workspace.root(), UUID.randomUUID());
    }
}
