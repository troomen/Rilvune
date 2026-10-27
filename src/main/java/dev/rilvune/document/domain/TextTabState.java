package dev.rilvune.document.domain;

import dev.rilvune.editor.domain.EditorTabState;

public record TextTabState(DocumentSnapshot document) implements EditorTabState { }
