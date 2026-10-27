package dev.rilvune.platform.theme;

import dev.rilvune.platform.text.domain.syntax.SyntaxKind;
import java.awt.Color;

/** Islands-inspired foreground tokens, adapted to the existing lexical categories rather than a theme-file format. */
public final class SyntaxColors {
    private static final Color KEYWORD = new Color(0xCF8E6D), FUNCTION = new Color(0x56A8F5),
            VARIABLE = new Color(0xC77DBB), STRING = new Color(0x6AAB73), NUMBER = new Color(0x2AACB8),
            ANNOTATION = new Color(0xB3AE60), MARKUP = new Color(0xD5B778), ERROR = new Color(0xFA6675);
    // Lift the reference's muted comments just enough to remain readable over diff/search and caret-row tints.
    private static final Color COMMENT = new Color(0x92969F);
    private SyntaxColors() { }
    public static Color color(SyntaxKind kind, EditorTheme theme) {
        return switch (kind) {
            case DEFAULT, TYPE, OPERATOR, ATTRIBUTE -> theme.text();
            case KEYWORD, LITERAL -> KEYWORD;
            case MARKUP -> MARKUP;
            case FUNCTION -> FUNCTION;
            case VARIABLE -> VARIABLE;
            case STRING -> STRING;
            case NUMBER -> NUMBER;
            case COMMENT -> COMMENT;
            case ANNOTATION -> ANNOTATION;
            case ERROR -> ERROR;
        };
    }
}
