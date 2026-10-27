package dev.rilvune.platform.text.domain.syntax;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntaxCatalogTest {
    @Test void customLanguagesNeedNoCoreEnumOrResolverChanges() {
        var catalog = new SyntaxCatalog(List.of(FixtureSyntax.contribution()));
        var resolver = new SyntaxLanguageResolver(catalog);
        assertEquals(FixtureSyntax.ID, resolver.resolve(SyntaxSource.file("sample.FIXTURE"), SyntaxSelection.AUTO, ""));
        assertEquals(SyntaxLanguageId.PLAIN, resolver.resolve(SyntaxSource.draft(), SyntaxSelection.AUTO, "paint"));
        assertEquals(FixtureSyntax.ID, resolver.resolve(SyntaxSource.draft(), SyntaxSelection.explicit(FixtureSyntax.ID), ""));
        assertEquals(SyntaxLanguageId.PLAIN, resolver.resolve(SyntaxSource.derived(), SyntaxSelection.explicit(FixtureSyntax.ID), ""));
        assertThrows(IllegalArgumentException.class, () -> new SyntaxCatalog(List.of(FixtureSyntax.contribution(), FixtureSyntax.contribution())));
    }
    @Test void precedenceCompoundSuffixesAndNormalizedConflictsAreDeterministic() {
        var a = language("a", SyntaxAssociation.name("Dockerfile"), SyntaxAssociation.suffix(".json"));
        var b = language("b", SyntaxAssociation.prefix("Dockerfile."), SyntaxAssociation.suffix(".tf.json"));
        var catalog = catalog(a, b); var resolver = new SyntaxLanguageResolver(catalog);
        assertEquals(a.id(), resolver.resolve(SyntaxSource.file("Dockerfile"), SyntaxSelection.AUTO, ""));
        assertEquals(b.id(), resolver.resolve(SyntaxSource.file("Dockerfile.prod"), SyntaxSelection.AUTO, ""));
        assertEquals(b.id(), resolver.resolve(SyntaxSource.file("main.TF.JSON"), SyntaxSelection.AUTO, ""));
        assertEquals(a.id(), resolver.resolve(SyntaxSource.file("main.tf.json"), SyntaxSelection.explicit(a.id()), ""));
        assertThrows(IllegalArgumentException.class, () -> catalog(a, language("c", SyntaxAssociation.suffix(".JSON"))));
        assertEquals(SyntaxLanguageId.PLAIN, resolver.resolve(SyntaxSource.file("name\nwith-control"), SyntaxSelection.AUTO, ""));
    }
    @Test void descriptorsAndProvidersMustAgreeAndReservedIdsCannotBeReplaced() {
        var provider = FixtureSyntax.contribution().provider();
        assertThrows(IllegalArgumentException.class, () -> new SyntaxContribution(provider, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SyntaxContribution(provider, List.of(SyntaxLanguage.PLAIN)));
        assertThrows(IllegalArgumentException.class, () -> new SyntaxLanguageId("Invalid ID"));
        assertThrows(IllegalArgumentException.class, () -> new SyntaxLanguageId("x".repeat(65)));
    }
    @Test void suffixDetectionIsIndependentOfTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var language = language("ini", SyntaxAssociation.suffix(".ini"));
            assertEquals(language.id(), new SyntaxLanguageResolver(catalog(language)).resolve(SyntaxSource.file("SETTINGS.INI"), SyntaxSelection.AUTO, ""));
        } finally { Locale.setDefault(previous); }
    }
    private static SyntaxLanguage language(String id, SyntaxAssociation... rules) { return new SyntaxLanguage(new SyntaxLanguageId(id), id, List.of(rules)); }
    private static SyntaxCatalog catalog(SyntaxLanguage... languages) {
        Set<SyntaxLanguageId> ids = new HashSet<>(); for (var language : languages) ids.add(language.id());
        return new SyntaxCatalog(List.of(new SyntaxContribution(new SyntaxLexerProvider() {
            public Set<SyntaxLanguageId> languages() { return Set.copyOf(ids); }
            public SyntaxLexer create(SyntaxLanguageId id) { throw new UnsupportedOperationException(); }
        }, List.of(languages))));
    }
}
