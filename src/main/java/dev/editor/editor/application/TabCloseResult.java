package dev.editor.editor.application;

import java.util.Set;

/** Describes actual releases, including a partial failure. */
public record TabCloseResult(Set<String> closedIds, Throwable failure) {
    public TabCloseResult { closedIds = Set.copyOf(closedIds); }
}
