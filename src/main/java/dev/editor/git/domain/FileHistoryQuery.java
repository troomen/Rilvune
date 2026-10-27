package dev.editor.git.domain;

import java.util.Objects;

/** A file or line history anchored to one immutable commit. Lines are zero-based, half-open. */
public record FileHistoryQuery(String startCommit, String path, Lines selection) {
    public FileHistoryQuery {
        GitSource.tree(startCommit, startCommit);
        Objects.requireNonNull(path);
        if (selection != null && selection.first() == selection.end()) throw new IllegalArgumentException("Empty selection");
    }
    public record Lines(int first, int end) {
        public Lines {
            if (first < 0 || end < first) throw new IllegalArgumentException("Invalid line range");
        }
    }
}
