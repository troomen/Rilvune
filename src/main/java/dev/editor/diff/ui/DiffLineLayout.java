package dev.editor.diff.ui;

import dev.editor.diff.domain.*;
import dev.editor.platform.text.ui.TextLineLayout;
import java.util.*;

/** Layout plans contain line coordinates only. They never transform or copy source text. */
final class DiffLineLayout {
    record FoldPair(TextLineLayout.Fold left, TextLineLayout.Fold right) { }
    record Pair(TextLineLayout left, TextLineLayout right, List<FoldPair> folds) { }
    static Pair compute(DiffResult result, boolean compact, Set<Integer> expanded) {
        Map<Integer, Integer> leftPadding = new HashMap<>(), rightPadding = new HashMap<>();
        List<FoldPair> folds = new ArrayList<>(); int left = 0, right = 0, leftSpace = 0, rightSpace = 0;
        for (DiffHunk hunk : result.hunks()) {
            fold(left, hunk.left().firstLine(), right, hunk.right().firstLine(), compact, expanded, folds);
            for (DiffLineChange change : hunk.changes()) {
                int delta = change.left().firstLine() + leftSpace - change.right().firstLine() - rightSpace;
                if (delta < 0) { leftPadding.merge(change.left().firstLine(), -delta, Integer::sum); leftSpace -= delta; }
                if (delta > 0) { rightPadding.merge(change.right().firstLine(), delta, Integer::sum); rightSpace += delta; }
                delta = change.left().endLine() + leftSpace - change.right().endLine() - rightSpace;
                if (delta < 0) { leftPadding.merge(change.left().endLine(), -delta, Integer::sum); leftSpace -= delta; }
                if (delta > 0) { rightPadding.merge(change.right().endLine(), delta, Integer::sum); rightSpace += delta; }
            }
            left = hunk.left().endLine(); right = hunk.right().endLine();
        }
        fold(left, result.lines(Side.LEFT).size(), right, result.lines(Side.RIGHT).size(), compact, expanded, folds);
        return new Pair(new TextLineLayout(leftPadding, folds.stream().map(FoldPair::left).toList()), new TextLineLayout(rightPadding, folds.stream().map(FoldPair::right).toList()), List.copyOf(folds));
    }
    private static void fold(int first, int end, int otherFirst, int otherEnd, boolean compact, Set<Integer> expanded, List<FoldPair> result) {
        if (!compact || end - first < 12 || end - first != otherEnd - otherFirst || expanded.contains(first + 3)) return;
        result.add(new FoldPair(new TextLineLayout.Fold(first + 3, end - 3), new TextLineLayout.Fold(otherFirst + 3, otherEnd - 3)));
    }
}
