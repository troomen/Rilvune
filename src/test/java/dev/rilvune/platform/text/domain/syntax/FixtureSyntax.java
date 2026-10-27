package dev.rilvune.platform.text.domain.syntax;

import java.util.List;
import java.util.Set;

/** Tested example of a completely library-independent, in-repository language contribution. */
public final class FixtureSyntax {
    public static final SyntaxLanguageId ID = new SyntaxLanguageId("fixture.portable");
    private FixtureSyntax() { }
    public static SyntaxContribution contribution() {
        return new SyntaxContribution(new SyntaxLexerProvider() {
            public Set<SyntaxLanguageId> languages() { return Set.of(ID); }
            public SyntaxLexer create(SyntaxLanguageId language) {
                if (!ID.equals(language)) throw new IllegalArgumentException();
                return (input, budget) -> {
                    var result = SyntaxResult.builder(input, budget); String text = input.text(); boolean quoted = false;
                    int at = 0;
                    while (at < text.length()) {
                        budget.check();
                        if (text.charAt(at) == '\n') { at++; continue; }
                        int start = at; SyntaxKind kind;
                        if (quoted || text.charAt(at) == '"') {
                            if (!quoted) { quoted = true; at++; }
                            while (at < text.length() && text.charAt(at) != '\n') {
                                if (text.charAt(at++) == '"') { quoted = false; break; }
                                budget.check();
                            }
                            kind = SyntaxKind.STRING;
                        } else if (text.charAt(at) == '#') {
                            while (at < text.length() && text.charAt(at) != '\n') { at++; budget.check(); }
                            kind = SyntaxKind.COMMENT;
                        } else if (Character.isLetter(text.charAt(at))) {
                            while (at < text.length() && Character.isLetter(text.charAt(at))) { at++; budget.check(); }
                            kind = text.substring(start, at).equals("paint") ? SyntaxKind.KEYWORD : SyntaxKind.DEFAULT;
                        } else { at++; kind = Character.isDigit(text.charAt(start)) ? SyntaxKind.NUMBER : SyntaxKind.DEFAULT; }
                        result.add(start, at, kind);
                    }
                    return result.build();
                };
            }
        }, List.of(new SyntaxLanguage(ID, "Fixture portable", List.of(SyntaxAssociation.suffix(".fixture")))));
    }
}
