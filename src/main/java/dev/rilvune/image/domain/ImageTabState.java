package dev.rilvune.image.domain;

import dev.rilvune.editor.domain.EditorTabState;

import java.nio.file.Path;

public record ImageTabState(Path path, Phase phase, long generation, String format, int width, int height,
                            long encodedBytes, boolean stale, String message) implements EditorTabState {
    public enum Phase { LOADING, READY, FAILED, MISSING }
}
