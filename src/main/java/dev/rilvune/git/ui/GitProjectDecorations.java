package dev.rilvune.git.ui;

import dev.rilvune.git.domain.ProjectGitStatus;
import dev.rilvune.platform.decoration.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Git semantics are adapted here; the workspace renderer has no Git dependency. */
public final class GitProjectDecorations {
    private GitProjectDecorations() { }
    public static PathDecorations from(ProjectGitStatus snapshot) {
        Map<Path, PathDecoration> paths = new HashMap<>();
        snapshot.paths().forEach((path, state) -> paths.put(path, new PathDecoration(
                switch (state.kind()) {
                    case MODIFIED -> PathDecoration.Tone.MODIFIED;
                    case UNTRACKED -> PathDecoration.Tone.UNTRACKED;
                    case ADDED -> PathDecoration.Tone.ADDED;
                    case CONFLICT -> PathDecoration.Tone.CONFLICT;
                    case IGNORED -> PathDecoration.Tone.IGNORED;
                }, state.description()
                + (snapshot.phase() == ProjectGitStatus.Phase.STALE ? " · stale: " + snapshot.message() : ""))));
        return new PathDecorations(snapshot.project(), paths, snapshot.message());
    }
}
