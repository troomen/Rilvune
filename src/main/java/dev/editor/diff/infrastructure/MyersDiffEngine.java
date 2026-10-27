package dev.editor.diff.infrastructure;

import dev.editor.diff.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Composes library-backed line matching and readability-oriented refinement within one request budget. */
public final class MyersDiffEngine implements DiffEngine {
    private final DiffLimits limits;
    public MyersDiffEngine(DiffLimits limits) { this.limits = java.util.Objects.requireNonNull(limits); }

    @Override public DiffResult compare(TextSnapshot left, TextSnapshot right, DiffOptions options, BooleanSupplier cancelled) {
        DiffBudget budget = new DiffBudget(limits, cancelled);
        BoundedSequenceDiff sequences = new BoundedSequenceDiff(budget);
        DiffRefiner refiner = new DiffRefiner(sequences, budget);
        if (left.text().length() > limits.maximumCharacters() || right.text().length() > limits.maximumCharacters()) {
            throw new DiffLimitException("File is too large to compare interactively");
        }
        TextLines a = TextLines.parse(left.text(), limits.maximumLines(), budget::cancelled);
        TextLines b = TextLines.parse(right.text(), limits.maximumLines(), budget::cancelled);
        List<String> oldLines = normalized(a.tokens(), options, budget);
        List<String> newLines = normalized(b.tokens(), options, budget);
        List<DiffHunk> hunks = new ArrayList<>();
        for (var change : sequences.compare(oldLines, newLines, String::equals, true)) {
            TextRange first = a.range(change.leftStart(), change.leftEnd());
            TextRange second = b.range(change.rightStart(), change.rightEnd());
            hunks.add(new DiffHunk(first, second, refiner.refine(a, b, first, second, options)));
        }
        budget.check();
        return new DiffResult(left, right, a, b, hunks, options);
    }

    private List<String> normalized(List<String> lines, DiffOptions options, DiffBudget budget) {
        if (!options.ignoreWhitespace()) return lines;
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            StringBuilder value = new StringBuilder(line.length());
            for (int index = 0; index < line.length(); index++) {
                if ((index & 4095) == 0) budget.check();
                char character = line.charAt(index);
                if (character == '\n' || !Character.isWhitespace(character)) value.append(character);
            }
            result.add(value.toString());
        }
        return result;
    }
}
