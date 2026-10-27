package dev.rilvune.platform.text.domain.syntax;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicitly assembled and immutable. No global registry, reflection or classpath discovery. */
public final class SyntaxCatalog {
    private final Map<SyntaxLanguageId, SyntaxLanguage> languages;
    private final Map<SyntaxLanguageId, SyntaxLexerProvider> providers;
    private final Map<SyntaxAssociation, SyntaxLanguageId> associations;

    public SyntaxCatalog(List<SyntaxContribution> contributions) {
        Map<SyntaxLanguageId, SyntaxLanguage> descriptors = new HashMap<>();
        Map<SyntaxLanguageId, SyntaxLexerProvider> bindings = new HashMap<>();
        Map<SyntaxAssociation, SyntaxLanguageId> rules = new HashMap<>();
        descriptors.put(SyntaxLanguageId.PLAIN, SyntaxLanguage.PLAIN);
        for (var contribution : List.copyOf(contributions)) for (var language : contribution.languages()) {
            if (descriptors.putIfAbsent(language.id(), language) != null) throw new IllegalArgumentException("Duplicate syntax language: " + language.id());
            bindings.put(language.id(), contribution.provider());
            for (var rule : language.associations())
                if (rules.putIfAbsent(rule, language.id()) != null) throw new IllegalArgumentException("Conflicting syntax association: " + rule);
        }
        languages = Map.copyOf(descriptors); providers = Map.copyOf(bindings); associations = Map.copyOf(rules);
    }
    public SyntaxLanguage language(SyntaxLanguageId id) {
        SyntaxLanguage result = languages.get(Objects.requireNonNull(id));
        if (result == null) throw new IllegalArgumentException("Unknown syntax language: " + id);
        return result;
    }
    public SyntaxLexerProvider provider(SyntaxLanguageId id) {
        var result = providers.get(id);
        if (result == null) throw new IllegalArgumentException("No lexer for syntax language: " + id);
        return result;
    }
    Map<SyntaxAssociation, SyntaxLanguageId> associations() { return associations; }
}
