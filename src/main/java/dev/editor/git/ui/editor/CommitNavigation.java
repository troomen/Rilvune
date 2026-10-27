package dev.editor.git.ui.editor;

import dev.editor.git.domain.RepositoryLocation;

/** Explicit navigation intent; the application decides which existing host displays it. */
public record CommitNavigation(RepositoryLocation repository, String commitId, String historicalPath) {
    public CommitNavigation {
        java.util.Objects.requireNonNull(repository); java.util.Objects.requireNonNull(historicalPath);
        if (commitId == null || !commitId.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalArgumentException("Navigation requires a full commit ID");
    }
}
