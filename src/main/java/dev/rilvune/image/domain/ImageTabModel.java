package dev.rilvune.image.domain;

import dev.rilvune.editor.domain.EditorTabModel;

import java.nio.file.Path;
import java.util.Objects;

/** Single-owner model; immutable snapshots are published by its UI controller. */
public final class ImageTabModel implements EditorTabModel<ImageTabState> {
    private final String id;
    private ImageTabState state;
    public ImageTabModel(String id, Path path) {
        this.id = Objects.requireNonNull(id);
        state = new ImageTabState(path, ImageTabState.Phase.LOADING, 0, "", 0, 0, 0, false, "Loading…");
    }
    @Override public String id() { return id; }
    @Override public ImageTabState state() { return state; }
    public void update(ImageTabState state) { this.state = Objects.requireNonNull(state); }
}
