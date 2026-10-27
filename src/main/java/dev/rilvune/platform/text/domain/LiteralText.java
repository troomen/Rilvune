package dev.rilvune.platform.text.domain;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Literal matching preserves UTF-16 offsets; replacement never searches inserted text. */
public final class LiteralText {
    public static final int MAX_RESULT_CHARACTERS = 32 * 1024 * 1024;
    public record Query(String text, boolean matchCase, boolean wholeWord) {
        public Query { Objects.requireNonNull(text); }
        public boolean at(String source, int offset) {
            return !text.isEmpty() && offset >= 0 && offset <= source.length() - text.length()
                    && source.regionMatches(!matchCase, offset, text, 0, text.length())
                    && (!wholeWord || boundary(source, offset - 1) && boundary(source, offset + text.length()));
        }
    }
    public record Summary(int count, List<Integer> positions) {
        public Summary { positions = List.copyOf(positions); }
    }
    public record Replacement(String text, int matches) { }
    private LiteralText() { }

    public static Summary scan(String source, Query query, int retained, BooleanSupplier cancelled) {
        List<Integer> positions = new ArrayList<>();
        int count = walk(source, query, cancelled, offset -> { if (positions.size() < retained) positions.add(offset); });
        return new Summary(count, positions);
    }

    public static List<Integer> matches(String source, Query query, int maximum, BooleanSupplier cancelled) {
        List<Integer> positions = new ArrayList<>();
        if (query.text().isEmpty()) return positions;
        for (int offset = 0, steps = 0; offset <= source.length() - query.text().length() && positions.size() < maximum;) {
            if ((steps++ & 4095) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
            if (query.at(source, offset)) { positions.add(offset); offset += query.text().length(); }
            else offset++;
        }
        return positions;
    }

    public static int find(String source, Query query, int from, boolean forward, BooleanSupplier cancelled) {
        int[] first = {-1}, last = {-1}, chosen = {-1};
        walk(source, query, cancelled, offset -> {
            if (first[0] < 0) first[0] = offset;
            last[0] = offset;
            if ((forward && chosen[0] < 0 && offset >= from) || (!forward && offset < from)) chosen[0] = offset;
        });
        return chosen[0] >= 0 ? chosen[0] : forward ? first[0] : last[0];
    }

    public static Replacement replace(String source, Query query, String replacement, int maximumMatches,
                                      int maximumCharacters, BooleanSupplier cancelled) {
        int[] count = {0};
        walk(source, query, cancelled, offset -> {
            if (++count[0] > maximumMatches) throw new IllegalArgumentException("Too many replacements; refine the query");
        });
        long length = source.length() + (long) count[0] * (replacement.length() - query.text().length());
        if (length > maximumCharacters) throw new IllegalArgumentException("Replacement result exceeds the supported text size");
        if (count[0] == 0) return new Replacement(source, 0);
        StringBuilder result = new StringBuilder((int) length);
        int[] end = {0};
        walk(source, query, cancelled, offset -> {
            result.append(source, end[0], offset).append(replacement);
            end[0] = offset + query.text().length();
        });
        result.append(source, end[0], source.length());
        return new Replacement(result.toString(), count[0]);
    }

    public static String replaceAt(String source, Query query, String replacement, int offset, int maximumCharacters) {
        if (!query.at(source, offset)) throw new IllegalArgumentException("The search result changed. Search again before replacing.");
        long length = (long) source.length() - query.text().length() + replacement.length();
        if (length > maximumCharacters) throw new IllegalArgumentException("Replacement result exceeds the supported text size");
        return new StringBuilder((int) length).append(source, 0, offset).append(replacement)
                .append(source, offset + query.text().length(), source.length()).toString();
    }

    private static int walk(String source, Query query, BooleanSupplier cancelled, IntConsumer match) {
        if (query.text().isEmpty()) return 0;
        int count = 0;
        for (int offset = 0, steps = 0; offset <= source.length() - query.text().length();) {
            if ((steps++ & 4095) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
            if (query.at(source, offset)) { match.accept(offset); count++; offset += query.text().length(); }
            else offset++;
        }
        return count;
    }
    private static boolean boundary(String source, int offset) {
        return offset < 0 || offset >= source.length() || !(Character.isLetterOrDigit(source.charAt(offset)) || source.charAt(offset) == '_');
    }
}
