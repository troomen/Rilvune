package dev.editor.platform.text.domain.syntax;

import java.util.Arrays;
import java.util.Objects;

/** Immutable sparse UTF-16 spans. No retained source text or third-party objects. */
public final class SyntaxResult {
    private static final SyntaxKind[] KINDS = SyntaxKind.values();
    private final SyntaxLanguageId language;
    private final int length;
    private final int[] spans;

    private SyntaxResult(SyntaxLanguageId language, int length, int[] spans) {
        this.language = language; this.length = length; this.spans = spans;
    }
    public static SyntaxResult empty(SyntaxLanguageId language, int length) {
        if (length < 0) throw new IllegalArgumentException("Negative source length");
        return new SyntaxResult(Objects.requireNonNull(language), length, new int[0]);
    }
    public SyntaxLanguageId language() { return language; }
    public int length() { return length; }
    public int size() { return spans.length / 3; }
    public int start(int index) { return spans[index * 3]; }
    public int end(int index) { return spans[index * 3 + 1]; }
    public SyntaxKind kind(int index) { return KINDS[spans[index * 3 + 2]]; }
    public long payloadBytes() { return 4L * spans.length; }
    /** First span whose exclusive end is greater than the requested position. */
    public int firstIntersecting(int position) {
        int low = 0, high = size();
        while (low < high) { int mid = (low + high) >>> 1; if (end(mid) <= position) low = mid + 1; else high = mid; }
        return low;
    }
    public static Builder builder(SyntaxInput input, SyntaxWorkBudget budget) { return new Builder(input, budget); }

    public static final class Builder {
        private final SyntaxInput input;
        private final SyntaxWorkBudget budget;
        private int[] data;
        private int size, previousEnd;
        private boolean finished;

        private Builder(SyntaxInput input, SyntaxWorkBudget budget) {
            this.input = Objects.requireNonNull(input); this.budget = Objects.requireNonNull(budget);
            budget.checkInput(input.text());
            data = new int[budget.limits().spans() * 3];
        }
        public void add(int start, int end, SyntaxKind kind) {
            budget.check(); Objects.requireNonNull(kind);
            String text = input.text();
            if (finished || start < previousEnd || end <= start || end > text.length()
                    || splitsPair(text, start) || splitsPair(text, end)) throw SyntaxFailure.invalid();
            for (int i = start; i < end; i++) {
                if ((i & 1023) == 0) budget.check();
                if (text.charAt(i) == '\n' || text.charAt(i) == '\r') throw SyntaxFailure.invalid();
            }
            previousEnd = end;
            if (kind == SyntaxKind.DEFAULT) return;
            if (size > 0 && data[size * 3 - 2] == start && data[size * 3 - 1] == kind.ordinal()) {
                data[size * 3 - 2] = end; return;
            }
            if (size >= budget.limits().spans()) throw SyntaxFailure.limit("Syntax token count limit");
            int index = size++ * 3; data[index] = start; data[index + 1] = end; data[index + 2] = kind.ordinal();
        }
        public SyntaxResult build() {
            budget.check();
            if (finished) throw new IllegalStateException("Syntax result already built");
            finished = true;
            int[] result = Arrays.copyOf(data, size * 3); data = null;
            return new SyntaxResult(input.language(), input.text().length(), result);
        }
        private static boolean splitsPair(String text, int offset) {
            return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                    && Character.isLowSurrogate(text.charAt(offset));
        }
    }
}
