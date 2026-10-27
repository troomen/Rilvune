package dev.rilvune.platform.text.domain.syntax;

/** One-job scanner. All mutable lexical state is private to this instance. */
public interface SyntaxLexer extends AutoCloseable {
    SyntaxResult lex(SyntaxInput input, SyntaxWorkBudget budget);
    @Override default void close() { }
}
