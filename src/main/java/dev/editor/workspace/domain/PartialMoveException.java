package dev.editor.workspace.domain;

import java.io.IOException;
import java.nio.file.Path;

/** Destination is complete, but source cleanup failed during a cross-filesystem move. */
public final class PartialMoveException extends IOException {
    private final Path target;

    public PartialMoveException(Path source, Path target, IOException cause) {
        super("Copy completed at " + target + "; removing the original " + source
                + " was incomplete. Inspect both locations before retrying.", cause);
        this.target = target;
    }

    public Path target() { return target; }
}
