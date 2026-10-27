package dev.editor.platform.text.domain.syntax;

/** Stable application identity, independent of a particular lexer or MIME registry. */
public record SyntaxLanguageId(String value) {
    public static final SyntaxLanguageId PLAIN = new SyntaxLanguageId("plain");

    public SyntaxLanguageId {
        if (value == null || value.length() > 64 || !value.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid syntax language ID");
    }

    @Override public String toString() { return value; }
}
