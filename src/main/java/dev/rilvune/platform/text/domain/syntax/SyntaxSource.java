package dev.rilvune.platform.text.domain.syntax;

import java.util.Objects;

/** A host supplies identity/eligibility, never a file-reading capability. Empty basename denotes a draft. */
public record SyntaxSource(String basename, boolean complete) {
    public SyntaxSource {
        Objects.requireNonNull(basename);
        if (basename.indexOf('/') >= 0)
            throw new IllegalArgumentException("Syntax source requires a basename, not a path");
    }
    public static SyntaxSource file(String basename) { return new SyntaxSource(basename, true); }
    public static SyntaxSource draft() { return file(""); }
    public static SyntaxSource derived() { return new SyntaxSource("", false); }
}
