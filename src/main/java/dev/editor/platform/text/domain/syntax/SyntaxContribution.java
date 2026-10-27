package dev.editor.platform.text.domain.syntax;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record SyntaxContribution(SyntaxLexerProvider provider, List<SyntaxLanguage> languages) {
    public SyntaxContribution {
        Objects.requireNonNull(provider); languages = List.copyOf(languages);
        var ids = languages.stream().map(SyntaxLanguage::id).collect(Collectors.toSet());
        if (ids.size() != languages.size() || ids.contains(SyntaxLanguageId.PLAIN) || !ids.equals(provider.languages()))
            throw new IllegalArgumentException("Syntax contribution descriptor/provider mismatch");
    }
}
