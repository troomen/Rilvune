package dev.rilvune.diff.infrastructure;

import dev.rilvune.diff.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class MyersDiffEngineTest {
    private final DiffEngine engine = new MyersDiffEngine(DiffLimits.interactive());
    @Test void insertionDeletionReplacementEmptyFilesUnicodeAndFinalNewlinesUseExactOriginalRanges() {
        String[][] cases = {{"", ""}, {"", "hello\n"}, {"old\n", ""}, {"a\nb\nc\n", "a\nnew\nc\n"},
                {"same\n😀\nПривет\nlast", "same\n😃\nпривет\nnew\nlast\n"}, {"line", "line\n"}, {"line\n", "line"},
                {"a\nb\nc\nd\n", "zero\na\nc\nnew\nd\n"}};
        for (String[] pair : cases) {
            DiffResult result = compare(pair[0], pair[1], false);
            StringBuilder right = new StringBuilder(pair[1]);
            for (DiffHunk hunk : result.hunks().reversed()) right.replace(hunk.right().startOffset(), hunk.right().endOffset(),
                    pair[0].substring(hunk.left().startOffset(), hunk.left().endOffset()));
            assertEquals(pair[0], right.toString());
            StringBuilder left = new StringBuilder(pair[0]);
            for (DiffHunk hunk : result.hunks().reversed()) left.replace(hunk.left().startOffset(), hunk.left().endOffset(),
                    pair[1].substring(hunk.right().startOffset(), hunk.right().endOffset()));
            assertEquals(pair[1], left.toString());
            if (!pair[0].equals(pair[1])) assertFalse(result.hunks().isEmpty());
        }
    }
    @Test void whitespaceComparisonRetainsRawTransferTextAndFinalNewlineDistinction() {
        DiffResult equal = compare("  a = 1\n\tb = 2\n", "a=1\nb=2\n", true);
        assertTrue(equal.hunks().isEmpty());
        DiffResult changed = compare("  same\n    old value\n", "same\nnew\n", true);
        assertEquals(1, changed.hunks().size());
        assertEquals("    old value\n", changed.leftLines().text(changed.hunks().getFirst().left()));
        assertFalse(compare("line", "line\n", true).hunks().isEmpty());
    }
    @Test void mappingKeepsUnchangedLinesAlignedAcrossInsertionAndDeletion() {
        DiffResult result = compare("one\ntwo\nthree\n", "one\nextra\ntwo\nthree\n", false);
        assertEquals(2, result.mapLine(Side.LEFT, 1));
        assertEquals(3, result.mapLine(Side.LEFT, 2));
        assertEquals(1, result.mapLine(Side.RIGHT, 2));
    }
    @Test void explicitInputWorkAndCancellationLimitsTerminateInsideTheAlgorithm() {
        var small = new MyersDiffEngine(new DiffLimits(20, 4, 3, Duration.ofSeconds(2)));
        assertThrows(DiffLimitException.class, () -> small.compare(snapshot("a", "abcde\nfghij\nklmno\npqrst\n"), snapshot("b", ""), DiffOptions.exact(), () -> false));
        assertThrows(DiffLimitException.class, () -> small.compare(snapshot("a", "a\nb\nc\nd\ne\n"), snapshot("b", ""), DiffOptions.exact(), () -> false));
        assertThrows(DiffLimitException.class, () -> small.compare(snapshot("a", "a\nb\nc"), snapshot("b", "x\ny\nz"), DiffOptions.exact(), () -> false));
        assertThrows(CancellationException.class, () -> engine.compare(snapshot("a", "a"), snapshot("b", "b"), DiffOptions.exact(), () -> true));
    }
    @Test void identicalInputsDoNotSpendAnEditDistanceBudget() {
        var small = new MyersDiffEngine(new DiffLimits(10000, 1000, 1, Duration.ofSeconds(2)));
        String text = "identical\n".repeat(500);
        assertTrue(small.compare(snapshot("a", text), snapshot("b", text), DiffOptions.exact(), () -> false).hunks().isEmpty());
    }
    @Test void smallChangesInLargeCommonContextDoNotSpendBudgetOnUnchangedLines() {
        var bounded = new MyersDiffEngine(new DiffLimits(10_000_000, 50_000, 500, Duration.ofSeconds(2)));
        String prefix = "shared prefix\n".repeat(10_000), suffix = "shared suffix\n".repeat(10_000);
        DiffResult result = bounded.compare(snapshot("a", prefix + "before\n" + suffix), snapshot("b", prefix + "after\n" + suffix), DiffOptions.exact(), () -> false);
        assertEquals(1, result.hunks().size()); assertEquals(10_000, result.hunks().getFirst().left().firstLine());
    }
    private DiffResult compare(String a, String b, boolean whitespace) {
        return engine.compare(snapshot("a", a), snapshot("b", b), new DiffOptions(whitespace), () -> false);
    }
    private TextSnapshot snapshot(String id, String text) { return new TextSnapshot(id, 0, text); }
}
