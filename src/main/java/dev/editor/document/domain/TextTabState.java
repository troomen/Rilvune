package dev.editor.document.domain;

import dev.editor.editor.domain.EditorTabState;

public record TextTabState(DocumentSnapshot document) implements EditorTabState { }
