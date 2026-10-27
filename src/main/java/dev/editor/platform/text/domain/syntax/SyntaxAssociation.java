package dev.editor.platform.text.domain.syntax;

import java.util.Locale;
import java.util.Objects;

/** Bounded declarative detection rules; no regex, filesystem or executable callbacks. */
public record SyntaxAssociation(Kind kind, String value) {
    public enum Kind { NAME, PREFIX, SUFFIX, INTERPRETER }

    public SyntaxAssociation {
        Objects.requireNonNull(kind);
        if (value == null || value.isEmpty() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid syntax association");
        if (kind == Kind.SUFFIX) {
            if (!value.startsWith(".") || value.length() < 2) throw new IllegalArgumentException("Suffix must start with a dot");
            value = value.toLowerCase(Locale.ROOT);
        }
        if (kind == Kind.INTERPRETER && !value.matches("[a-z][a-z0-9.-]*"))
            throw new IllegalArgumentException("Invalid interpreter alias");
    }

    public static SyntaxAssociation name(String value) { return new SyntaxAssociation(Kind.NAME, value); }
    public static SyntaxAssociation prefix(String value) { return new SyntaxAssociation(Kind.PREFIX, value); }
    public static SyntaxAssociation suffix(String value) { return new SyntaxAssociation(Kind.SUFFIX, value); }
    public static SyntaxAssociation interpreter(String value) { return new SyntaxAssociation(Kind.INTERPRETER, value); }
}
