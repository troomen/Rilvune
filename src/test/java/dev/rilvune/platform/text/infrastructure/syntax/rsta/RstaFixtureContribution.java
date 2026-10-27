package dev.rilvune.platform.text.infrastructure.syntax.rsta;

import dev.rilvune.platform.text.domain.syntax.*;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import javax.swing.text.Segment;
import java.util.List;
import java.util.Map;

/** The UI consumes only this helper's domain return value, never RSTA types. */
public final class RstaFixtureContribution {
    public static final SyntaxLanguageId ID = new SyntaxLanguageId("fixture.rsta");
    private RstaFixtureContribution() { }
    public static SyntaxContribution contribution() {
        var language = new SyntaxLanguage(ID, "Fixture RSTA", List.of(SyntaxAssociation.suffix(".rfixture")));
        return new RstaSyntaxProvider(List.of(language), Map.of(ID, Maker::new)).contribution();
    }
    private static final class Maker extends AbstractTokenMaker {
        @Override public TokenMap getWordsToHighlight() { return new TokenMap(); }
        @Override public Token getTokenList(Segment text, int initial, int offset) {
            resetTokenList(); int state = initial, at = text.offset, end = at + text.count;
            while (at < end) {
                int from = at;
                if (state == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE || text.array[at] == '"') {
                    if (state != TokenTypes.LITERAL_STRING_DOUBLE_QUOTE) at++;
                    state = TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
                    while (at < end) if (text.array[at++] == '"') { state = TokenTypes.NULL; break; }
                    addToken(text.array, from, at - 1, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, offset + from - text.offset);
                } else {
                    while (at < end && text.array[at] != '"') at++;
                    addToken(text.array, from, at - 1, TokenTypes.RESERVED_WORD, offset + from - text.offset);
                }
            }
            if (state == TokenTypes.NULL) addNullToken();
            else if (text.count == 0) addToken(text.array, text.offset, text.offset - 1, state, offset);
            return firstToken;
        }
    }
}
