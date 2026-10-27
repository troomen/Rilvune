package dev.editor.platform.text.domain.syntax;

/** Foreground semantics, deliberately not a mirror of any backend's token-type table. */
public enum SyntaxKind {
    DEFAULT, KEYWORD, TYPE, FUNCTION, VARIABLE, STRING, NUMBER, LITERAL, COMMENT,
    ANNOTATION, OPERATOR, MARKUP, ATTRIBUTE, ERROR
}
