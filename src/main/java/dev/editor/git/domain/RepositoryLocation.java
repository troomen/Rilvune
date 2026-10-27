package dev.editor.git.domain;

import java.nio.file.Path;

/** Resolved metadata paths and an opaque identity distinguishing repository replacements. */
public record RepositoryLocation(Path root, Path gitDirectory, Path commonDirectory, String incarnation) {
    public RepositoryLocation(Path root, Path gitDirectory, Path commonDirectory) {
        this(root, gitDirectory, commonDirectory, "");
    }
}
