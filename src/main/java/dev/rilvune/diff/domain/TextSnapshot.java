package dev.rilvune.diff.domain;

import java.util.Objects;

/** Content and its version, independent of files, Git and Swing. Offsets are UTF-16. */
public record TextSnapshot(String sourceId, long revision, String text) {
    public TextSnapshot {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(text, "text");
        if (revision < 0) throw new IllegalArgumentException("Negative revision");
    }
}
