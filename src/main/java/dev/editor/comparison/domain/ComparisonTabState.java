package dev.editor.comparison.domain;

import dev.editor.editor.domain.EditorTabState;
import dev.editor.session.domain.SessionState;

public record ComparisonTabState(SessionState.ComparisonTabDescriptor descriptor, String explanation) implements EditorTabState { }
