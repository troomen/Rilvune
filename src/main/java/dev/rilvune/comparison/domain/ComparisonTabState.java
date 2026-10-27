package dev.rilvune.comparison.domain;

import dev.rilvune.editor.domain.EditorTabState;
import dev.rilvune.session.domain.SessionState;

public record ComparisonTabState(SessionState.ComparisonTabDescriptor descriptor, String explanation) implements EditorTabState { }
