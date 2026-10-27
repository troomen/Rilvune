package dev.editor.workspace.domain;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspaceEntry(Path path, String name, boolean directory) {
    public WorkspaceEntry {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Entry name must not be empty");
        }
    }
}
