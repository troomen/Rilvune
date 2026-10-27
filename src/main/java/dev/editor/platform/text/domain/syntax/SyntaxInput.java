package dev.editor.platform.text.domain.syntax;

import java.util.Objects;

public record SyntaxInput(SyntaxLanguageId language, String text) {
    public SyntaxInput { Objects.requireNonNull(language); Objects.requireNonNull(text); }
}
