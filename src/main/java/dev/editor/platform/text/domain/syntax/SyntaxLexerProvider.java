package dev.editor.platform.text.domain.syntax;

import java.util.Set;

/** Immutable provider bindings; create and lex are called only on workers. */
public interface SyntaxLexerProvider {
    Set<SyntaxLanguageId> languages();
    SyntaxLexer create(SyntaxLanguageId language);
}
