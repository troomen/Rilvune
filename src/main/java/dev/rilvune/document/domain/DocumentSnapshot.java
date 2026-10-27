package dev.rilvune.document.domain;

import java.nio.file.Path;
import java.util.UUID;

/** Immutable notifications are delivered on the explicitly supplied notification executor. */
public record DocumentSnapshot(UUID id, Path path, String text, long revision, SyncState state,
                               TextFormat format, String externalText, String message, String recoveryError, boolean recoveredPause) {
    public boolean recoveryFailed() { return recoveryError != null; }
    public String details() {
        if (!recoveryFailed()) return message;
        String recovery = "Latest editor text is not confirmed in recovery: " + recoveryError;
        return message == null ? recovery : message + "\n" + recovery;
    }
    public String name() { return path == null ? "Untitled" : path.getFileName().toString(); }
    public boolean needsAttention() {
        return state == SyncState.CONFLICT || state == SyncState.DELETED || state == SyncState.ERROR || recoveryFailed() || recoveredPause;
    }
}
