package dev.editor.platform.text.ui.syntax;

public record SyntaxStatus(State state, String explanation) {
    public enum State { PLAIN, DISABLED, PENDING, READY, LIMIT_EXCEEDED, CAPACITY_DEFERRED, LEXER_FAILED }
    public SyntaxStatus { java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(explanation); }
    static SyntaxStatus of(State state, String explanation) { return new SyntaxStatus(state, explanation); }
}
