package dev.editor.diff.domain;

public record DiffOptions(boolean ignoreWhitespace) {
    public static DiffOptions exact() { return new DiffOptions(false); }
}
