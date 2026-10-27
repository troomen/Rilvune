package dev.editor.diff.infrastructure;

import dev.editor.diff.domain.DiffFragment;
import dev.editor.diff.domain.TextSpan;
import java.util.ArrayList;
import java.util.List;

/**
 * Readability-oriented inline highlights, not a minimum character edit script.
 * Regions may include unchanged characters; their ordered source ranges remain exact.
 */
final class InlineDiffRefiner {
    private static final int MAXIMUM_SEPARATOR_GAP = 3;
    private final BoundedSequenceDiff sequences;
    private final DiffBudget budget;

    InlineDiffRefiner(BoundedSequenceDiff sequences, DiffBudget budget) {
        this.sequences = sequences; this.budget = budget;
    }

    List<DiffFragment> refine(String before, int beforeOffset, String after, int afterOffset, boolean ignoreWhitespace) {
        TextSequence left = new TextSequence(before, beforeOffset, ignoreWhitespace, budget);
        TextSequence right = new TextSequence(after, afterOffset, ignoreWhitespace, budget);
        List<DiffFragment> result = new ArrayList<>();
        for (var block : sequences.compare(left.words, right.words, (a, b) -> a.text().equals(b.text()), true)) {
            int a = left.unitBoundary(block.leftStart()), endA = left.unitBoundary(block.leftEnd());
            int b = right.unitBoundary(block.rightStart()), endB = right.unitBoundary(block.rightEnd());
            if (correspondingTokens(left, right, block)) {
                for (var edit : sequences.compare(left.units.subList(a, endA), right.units.subList(b, endB),
                        (one, two) -> one.value() == two.value(), true)) {
                    result.add(new DiffFragment(left.span(a + edit.leftStart(), a + edit.leftEnd()),
                            right.span(b + edit.rightStart(), b + edit.rightEnd())));
                }
            } else result.add(new DiffFragment(left.span(a, endA), right.span(b, endB)));
        }
        return groupSeparators(result, before, beforeOffset, after, afterOffset);
    }

    private boolean correspondingTokens(TextSequence left, TextSequence right, BoundedSequenceDiff.Change block) {
        if (block.leftEnd() - block.leftStart() != 1 || block.rightEnd() - block.rightStart() != 1) return false;
        var a = left.words.get(block.leftStart()); var b = right.words.get(block.rightStart());
        if (!a.identifierLike() || !b.identifierLike()) return false;
        if (digits(left, a) && digits(right, b)) return true;
        // Similar token edges justify fine detail. Interior letter coincidences do not.
        int shorter = Math.min(a.end() - a.first(), b.end() - b.first()), prefix = 0, suffix = 0;
        while (prefix < shorter) {
            budget.work(1);
            if (left.units.get(a.first() + prefix).value() != right.units.get(b.first() + prefix).value()) break;
            prefix++;
        }
        while (suffix < shorter - prefix) {
            budget.work(1);
            if (left.units.get(a.end() - suffix - 1).value() != right.units.get(b.end() - suffix - 1).value()) break;
            suffix++;
        }
        return 2 * (prefix + suffix) >= shorter;
    }

    private boolean digits(TextSequence sequence, TextSequence.Word word) {
        for (int index = word.first(); index < word.end(); index++) {
            budget.work(1);
            if (!Character.isDigit(sequence.units.get(index).value())) return false;
        }
        return true;
    }

    private List<DiffFragment> groupSeparators(List<DiffFragment> fragments, String left, int leftOffset, String right, int rightOffset) {
        List<DiffFragment> grouped = new ArrayList<>();
        for (DiffFragment next : fragments) {
            budget.work(1);
            if (!grouped.isEmpty()) {
                DiffFragment prior = grouped.getLast();
                if (smallSeparatorGap(left, prior.left().endOffset() - leftOffset, next.left().startOffset() - leftOffset)
                        && smallSeparatorGap(right, prior.right().endOffset() - rightOffset, next.right().startOffset() - rightOffset)) {
                    grouped.set(grouped.size() - 1, new DiffFragment(
                            new TextSpan(prior.left().startOffset(), next.left().endOffset()),
                            new TextSpan(prior.right().startOffset(), next.right().endOffset())));
                    continue;
                }
            }
            grouped.add(next);
        }
        return grouped;
    }

    private boolean smallSeparatorGap(String text, int first, int end) {
        int count = 0;
        for (int offset = first; offset < end;) {
            budget.work(1);
            int value = text.codePointAt(offset); offset += Character.charCount(value);
            if (++count > MAXIMUM_SEPARATOR_GAP || !groupableSeparator(value)) return false;
        }
        return true;
    }

    private static boolean groupableSeparator(int value) {
        return switch (value) {
            case '.', ',', '(', ')', '[', ']' -> true;
            default -> value != '\n' && value != '\r' && Character.isWhitespace(value);
        };
    }
}
