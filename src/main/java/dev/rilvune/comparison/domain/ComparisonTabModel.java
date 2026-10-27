package dev.rilvune.comparison.domain;

import dev.rilvune.editor.domain.EditorTabModel;
import dev.rilvune.session.domain.SessionState;

import java.util.function.Supplier;

public record ComparisonTabModel(String id, Supplier<SessionState.ComparisonTabDescriptor> snapshot, Supplier<String> explanation) implements EditorTabModel<ComparisonTabState> {
    public ComparisonTabModel(String id, Supplier<SessionState.ComparisonTabDescriptor> snapshot) { this(id, snapshot, () -> ""); }
    @Override public ComparisonTabState state() { return new ComparisonTabState(snapshot.get(), explanation.get()); }
}
