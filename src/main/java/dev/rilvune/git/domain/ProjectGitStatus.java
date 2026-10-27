package dev.rilvune.git.domain;

import java.nio.file.Path;
import java.util.Map;

/** A complete immutable publication for one project. Missing entries are neutral, not verified clean. */
public record ProjectGitStatus(Path project, RepositoryLocation location, LocalGitStatus local,
                               Map<Path, GitPathStatus> paths, Phase phase, String message) {
    public enum Phase { LOADING, READY, NO_REPOSITORY, UNAVAILABLE, STALE }
    public ProjectGitStatus { paths = Map.copyOf(paths); }
    public boolean available() { return location != null && local != null; }
    public static ProjectGitStatus empty(Path root, Phase phase, String message) {
        return new ProjectGitStatus(root, null, null, Map.of(), phase, message);
    }
}
