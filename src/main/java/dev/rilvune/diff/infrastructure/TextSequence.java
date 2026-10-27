package dev.rilvune.diff.infrastructure;

import dev.rilvune.diff.domain.TextSpan;
import java.util.ArrayList;
import java.util.List;

/** Comparison units retain exact source positions even when whitespace is omitted. */
final class TextSequence {
    record Unit(int value, int start, int end) { }
    record Word(String text, int first, int end) {
        boolean identifierLike() { return identifierCodePoint(text.codePointAt(0)); }
    }
    final List<Unit> units;
    final List<Word> words;
    private final int sourceEnd;

    TextSequence(String text, int sourceStart, boolean ignoreWhitespace, DiffBudget budget) {
        units = new ArrayList<>(); words = new ArrayList<>(); sourceEnd = sourceStart + text.length();
        for (int offset = 0; offset < text.length();) {
            budget.work(1); int value = text.codePointAt(offset), width = Character.charCount(value);
            if (!ignoreWhitespace || value == '\n' || !Character.isWhitespace(value)) units.add(new Unit(value, sourceStart + offset, sourceStart + offset + width));
            offset += width;
        }
        for (int first = 0; first < units.size();) {
            budget.work(1); int end = first + 1, category = category(units.get(first).value());
            if (category != 0) while (end < units.size() && category(units.get(end).value()) == category) { budget.work(1); end++; }
            StringBuilder word = new StringBuilder();
            for (int index = first; index < end; index++) {
                if ((index & 1023) == 0) budget.check();
                word.appendCodePoint(units.get(index).value());
            }
            words.add(new Word(word.toString(), first, end)); first = end;
        }
    }
    int unitBoundary(int word) { return word < words.size() ? words.get(word).first() : units.size(); }
    TextSpan span(int first, int end) {
        if (first == end) {
            int offset = first < units.size() ? units.get(first).start() : sourceEnd;
            return new TextSpan(offset, offset);
        }
        return new TextSpan(units.get(first).start(), units.get(end - 1).end());
    }
    static boolean identifierCodePoint(int value) {
        int type = Character.getType(value);
        return Character.isLetterOrDigit(value) || value == '_' || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
    private static int category(int value) {
        if (identifierCodePoint(value)) return 1;
        return value != '\n' && Character.isWhitespace(value) ? 2 : 0;
    }
}
