package dev.editor.diff.infrastructure;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.algorithm.myers.MyersDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

/** The only sequence-algorithm adapter; reused at every refinement level. */
final class BoundedSequenceDiff {
    record Change(int leftStart, int leftEnd, int rightStart, int rightEnd) { }
    private final DiffBudget budget;
    BoundedSequenceDiff(DiffBudget budget) { this.budget = budget; }
    <T> List<Change> compare(List<T> left, List<T> right, BiPredicate<T, T> equal, boolean trimEqualEdges) {
        budget.check(); int prefix = 0, suffix = 0;
        if (trimEqualEdges) {
            while (prefix < Math.min(left.size(), right.size())) {
                if ((prefix & 127) == 0) budget.check();
                if (!equal.test(left.get(prefix), right.get(prefix))) break;
                prefix++;
            }
            while (suffix < Math.min(left.size(), right.size()) - prefix) {
                if ((suffix & 127) == 0) budget.check();
                if (!equal.test(left.get(left.size() - suffix - 1), right.get(right.size() - suffix - 1))) break;
                suffix++;
            }
        }
        if (prefix == left.size() && prefix == right.size()) return List.of();
        var patch = DiffUtils.diff(left.subList(prefix, left.size() - suffix), right.subList(prefix, right.size() - suffix),
                new MyersDiff<T>((a, b) -> { budget.work(1); return equal.test(a, b); }), new DiffAlgorithmListener() {
                    @Override public void diffStart() { budget.check(); }
                    @Override public void diffStep(int value, int maximum) { budget.work(value + 1L); }
                    @Override public void diffEnd() { budget.check(); }
                });
        List<Change> result = new ArrayList<>();
        for (var delta : patch.getDeltas()) {
            int a = prefix + delta.getSource().getPosition(), b = prefix + delta.getTarget().getPosition();
            result.add(new Change(a, a + delta.getSource().size(), b, b + delta.getTarget().size()));
        }
        result.sort(Comparator.comparingInt(Change::leftStart).thenComparingInt(Change::rightStart));
        budget.check(); return result;
    }
}
