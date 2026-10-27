package dev.editor.platform.text.domain.syntax;

import java.util.Optional;

/** Auto is distinct from an explicit plain-text override. */
public record SyntaxSelection(Optional<SyntaxLanguageId> language) {
    public static final SyntaxSelection AUTO = new SyntaxSelection(Optional.empty());
    public SyntaxSelection { language = java.util.Objects.requireNonNull(language); }
    public static SyntaxSelection explicit(SyntaxLanguageId language) { return new SyntaxSelection(Optional.of(language)); }
}
