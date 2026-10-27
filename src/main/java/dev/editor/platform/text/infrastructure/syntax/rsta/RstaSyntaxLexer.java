package dev.editor.platform.text.infrastructure.syntax.rsta;

import dev.editor.platform.text.domain.syntax.*;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;

/** RSTA's reusable token lists and continuation markers never leave the job. */
final class RstaSyntaxLexer implements SyntaxLexer {
    private final SyntaxLanguageId language;
    private final TokenMaker maker;
    RstaSyntaxLexer(SyntaxLanguageId language, TokenMaker maker) { this.language = language; this.maker = maker; }

    @Override public SyntaxResult lex(SyntaxInput input, SyntaxWorkBudget budget) {
        if (!input.language().equals(language)) throw SyntaxFailure.invalid();
        var result = SyntaxResult.builder(input, budget);
        char[] characters = input.text().toCharArray();
        int from = 0, state = TokenTypes.NULL;
        do {
            budget.check();
            int end = from;
            while (end < characters.length && characters[end] != '\n') end++;
            Segment line = new Segment(characters, from, end - from);
            Token token = maker.getTokenList(line, state, from);
            int count = 0;
            if (token == null) throw SyntaxFailure.invalid();
            for (;;) {
                budget.check();
                if (++count > end - from + 32) throw SyntaxFailure.invalid();
                if (token.isPaintable() && token.length() > 0) {
                    int start = token.getOffset(), stop = token.getEndOffset();
                    if (start < from || stop > end) throw SyntaxFailure.invalid();
                    result.add(start, stop, RstaTokenKinds.kind(token.getType()));
                }
                Token next = token.getNextToken();
                if (next == null) { state = token.getType(); break; }
                token = next;
            }
            // The terminal type is precisely RSTA's default continuation contract, including negative states.
            // Do not call getLastTokenTypeOnLine(): its default re-lexes and reuses the token buffer.
            if (end == characters.length) break;
            from = end + 1;
        } while (from <= characters.length);
        return result.build();
    }
}
