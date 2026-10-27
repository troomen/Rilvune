package dev.editor.diff.infrastructure;

import dev.editor.diff.domain.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Alignment is library-provided; similarity and tokenization are editor presentation policies. */
final class DiffRefiner {
    private static final double MINIMUM_LINE_SIMILARITY = 0.6;
    private final BoundedSequenceDiff sequences;
    private final DiffBudget budget;
    DiffRefiner(BoundedSequenceDiff sequences, DiffBudget budget) { this.sequences = sequences; this.budget = budget; }

    List<DiffLineChange> refine(TextLines left, TextLines right, TextRange a, TextRange b, DiffOptions options) {
        if (a.empty() || b.empty()) return List.of(new DiffLineChange(a, b, List.of()));
        List<Line> oldLines = lines(left, a), newLines = lines(right, b);
        if (oldLines.size() == 1 && newLines.size() == 1) {
            List<DiffLineChange> changes = new ArrayList<>();
            corresponding(changes, left, right, oldLines.getFirst(), newLines.getFirst(), options);
            return List.copyOf(changes);
        }
        var unmatched = sequences.compare(oldLines, newLines, this::similar, false);
        List<DiffLineChange> changes = new ArrayList<>(); int x = 0, y = 0;
        for (var change : unmatched) {
            while (x < change.leftStart() && y < change.rightStart()) {
                corresponding(changes, left, right, oldLines.get(x++), newLines.get(y++), options);
            }
            int oldStart = a.firstLine() + change.leftStart(), oldEnd = a.firstLine() + change.leftEnd();
            int newStart = b.firstLine() + change.rightStart(), newEnd = b.firstLine() + change.rightEnd();
            if (oldStart < oldEnd) changes.add(new DiffLineChange(left.range(oldStart, oldEnd), right.range(newStart, newStart), List.of()));
            if (newStart < newEnd) changes.add(new DiffLineChange(left.range(oldEnd, oldEnd), right.range(newStart, newEnd), List.of()));
            x = change.leftEnd(); y = change.rightEnd();
        }
        while (x < oldLines.size() && y < newLines.size()) corresponding(changes, left, right, oldLines.get(x++), newLines.get(y++), options);
        budget.check(); return List.copyOf(changes);
    }
    private List<Line> lines(TextLines source, TextRange range) {
        List<Line> result = new ArrayList<>();
        for (int line = range.firstLine(); line < range.endLine(); line++) {
            budget.work(1); result.add(new Line(line, source.tokens().get(line)));
        }
        return result;
    }
    private boolean similar(Line a, Line b) {
        a.countCharacters(budget); b.countCharacters(budget);
        if (a.characters + b.characters == 0) return true;
        int prefix = 0, offset = 0;
        while (offset < Math.min(a.semantic.length(), b.semantic.length())) {
            budget.work(1); int value = a.semantic.codePointAt(offset);
            if (value != b.semantic.codePointAt(offset)) break;
            offset += Character.charCount(value); prefix++;
        }
        // Preserve clear shared prefixes such as assignment keys even if their values differ greatly.
        if (prefix >= 2 && 2 * prefix >= Math.min(a.characters, b.characters)) return true;
        int common = 0;
        Map<Integer, Integer> smaller = a.counts.size() <= b.counts.size() ? a.counts : b.counts;
        Map<Integer, Integer> larger = smaller == a.counts ? b.counts : a.counts;
        for (var entry : smaller.entrySet()) { budget.work(1); common += Math.min(entry.getValue(), larger.getOrDefault(entry.getKey(), 0)); }
        return 2.0 * common / (a.characters + b.characters) >= MINIMUM_LINE_SIMILARITY;
    }
    private void corresponding(List<DiffLineChange> changes, TextLines left, TextLines right, Line a, Line b, DiffOptions options) {
        boolean oldNewline = a.text.endsWith("\n"), newNewline = b.text.endsWith("\n");
        int oldBodyLength = a.text.length() - (oldNewline ? 1 : 0), newBodyLength = b.text.length() - (newNewline ? 1 : 0);
        // Minified files can have very long lines: do not tokenize their identical edges.
        int prefix = 0, oldEnd = oldBodyLength, newEnd = newBodyLength, inspected = 0;
        while (prefix < Math.min(oldEnd, newEnd)) {
            if ((inspected++ & 4095) == 0) budget.check();
            int value = a.text.codePointAt(prefix);
            if (value != b.text.codePointAt(prefix)) break;
            prefix += Character.charCount(value);
        }
        while (oldEnd > prefix && newEnd > prefix) {
            if ((inspected++ & 4095) == 0) budget.check();
            int value = a.text.codePointBefore(oldEnd);
            if (value != b.text.codePointBefore(newEnd)) break;
            oldEnd -= Character.charCount(value); newEnd -= Character.charCount(value);
        }
        List<DiffFragment> fragments = new ArrayList<>(new InlineDiffRefiner(sequences, budget).refine(
                a.text.substring(prefix, oldEnd), left.offset(a.number) + prefix,
                b.text.substring(prefix, newEnd), right.offset(b.number) + prefix, options.ignoreWhitespace()));
        if (oldNewline != newNewline) fragments.add(new DiffFragment(
                new TextSpan(left.offset(a.number) + oldBodyLength, left.offset(a.number) + a.text.length()),
                new TextSpan(right.offset(b.number) + newBodyLength, right.offset(b.number) + b.text.length())));
        if (!fragments.isEmpty()) changes.add(new DiffLineChange(left.range(a.number, a.number + 1), right.range(b.number, b.number + 1), fragments));
    }
    private static final class Line {
        private final int number;
        private final String text;
        private Map<Integer, Integer> counts;
        private String semantic;
        private int characters;
        private Line(int number, String text) { this.number = number; this.text = text; }
        private void countCharacters(DiffBudget budget) {
            if (counts != null) return;
            counts = new HashMap<>();
            StringBuilder content = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                budget.work(1); int value = text.codePointAt(offset); offset += Character.charCount(value);
                if (!Character.isWhitespace(value)) { counts.merge(value, 1, Integer::sum); characters++; content.appendCodePoint(value); }
            }
            semantic = content.toString();
        }
    }
}
