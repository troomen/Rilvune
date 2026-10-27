package dev.rilvune.document.domain;

import dev.rilvune.editor.domain.EditorTabModel;

import java.util.function.Supplier;

/** Projection of the existing session, not a second mutable buffer. */
public record TextTabModel(String id, Supplier<DocumentSnapshot> snapshot) implements EditorTabModel<TextTabState> {
    @Override public TextTabState state() { return new TextTabState(snapshot.get()); }
}
