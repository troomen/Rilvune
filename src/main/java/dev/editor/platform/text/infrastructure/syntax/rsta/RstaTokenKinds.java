package dev.editor.platform.text.infrastructure.syntax.rsta;

import dev.editor.platform.text.domain.syntax.SyntaxKind;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/** Explicit, upgrade-reviewed translation; no numeric token types escape this adapter. */
final class RstaTokenKinds {
    private RstaTokenKinds() { }
    static SyntaxKind kind(int type) {
        return switch (type) {
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2 -> SyntaxKind.KEYWORD;
            case TokenTypes.DATA_TYPE -> SyntaxKind.TYPE;
            case TokenTypes.FUNCTION -> SyntaxKind.FUNCTION;
            case TokenTypes.VARIABLE -> SyntaxKind.VARIABLE;
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
                    TokenTypes.REGEX, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE -> SyntaxKind.STRING;
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT, TokenTypes.LITERAL_NUMBER_HEXADECIMAL -> SyntaxKind.NUMBER;
            case TokenTypes.LITERAL_BOOLEAN, TokenTypes.MARKUP_ENTITY_REFERENCE -> SyntaxKind.LITERAL;
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
                    TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT -> SyntaxKind.COMMENT;
            case TokenTypes.ANNOTATION, TokenTypes.PREPROCESSOR -> SyntaxKind.ANNOTATION;
            case TokenTypes.OPERATOR, TokenTypes.SEPARATOR -> SyntaxKind.OPERATOR;
            case TokenTypes.MARKUP_TAG_DELIMITER, TokenTypes.MARKUP_TAG_NAME, TokenTypes.MARKUP_DTD,
                    TokenTypes.MARKUP_PROCESSING_INSTRUCTION, TokenTypes.MARKUP_CDATA_DELIMITER -> SyntaxKind.MARKUP;
            case TokenTypes.MARKUP_TAG_ATTRIBUTE -> SyntaxKind.ATTRIBUTE;
            case TokenTypes.ERROR_IDENTIFIER, TokenTypes.ERROR_NUMBER_FORMAT, TokenTypes.ERROR_STRING_DOUBLE, TokenTypes.ERROR_CHAR -> SyntaxKind.ERROR;
            default -> SyntaxKind.DEFAULT;
        };
    }
}
