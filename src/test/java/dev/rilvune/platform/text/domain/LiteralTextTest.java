package dev.rilvune.platform.text.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class LiteralTextTest {
    private final LiteralText.Query a = new LiteralText.Query("a", true, false);

    @Test void manyMatchesKeepOnlyBoundedPositionsAndReplaceOriginalOccurrences() {
        String source = "a".repeat(50000);
        var summary = LiteralText.scan(source, a, 2000, () -> false);
        assertEquals(50000, summary.count()); assertEquals(2000, summary.positions().size());
        var doubled = LiteralText.replace(source, a, "aa", 50000, 100000, () -> false);
        assertEquals(50000, doubled.matches()); assertEquals("a".repeat(100000), doubled.text());
        assertEquals("", LiteralText.replace(source, a, "", 50000, 100000, () -> false).text());
    }

    @Test void unicodeWordsAndNavigationUseStableOffsets() {
        assertEquals(List.of(2), LiteralText.matches("İ hello", new LiteralText.Query("hello", false, false), 20, () -> false));
        String source = "İa a_ a A 😀a";
        var needle = new LiteralText.Query("a", false, true);
        assertEquals(List.of(6, 8, 12), LiteralText.scan(source, needle, 10, () -> false).positions());
        assertEquals(6, LiteralText.find(source, needle, 13, true, () -> false));
        assertEquals(12, LiteralText.find(source, needle, 0, false, () -> false));
        assertEquals(8, LiteralText.find(source, needle, 9, false, () -> false));
        var overlapping = new LiteralText.Query("aa", true, false);
        assertEquals("ba", LiteralText.replace("aaa", overlapping, "b", 10, 10, () -> false).text());
    }

    @Test void limitsFailTheWholeOperationBeforeReturningATruncatedResult() {
        assertThrows(IllegalArgumentException.class, () -> LiteralText.replace("aaa", a, "b", 2, 100, () -> false));
        assertThrows(IllegalArgumentException.class, () -> LiteralText.replace("aaa", a, "bbbb", 3, 11, () -> false));
        assertEquals("bbbbbbbbbbbb", LiteralText.replace("aaa", a, "bbbb", 3, 12, () -> false).text());
        assertThrows(IllegalArgumentException.class, () -> LiteralText.replaceAt("aaa", a, "bbbb", 0, 5));
    }

    @Test void cancellationInterruptsMatchingAndConstruction() {
        assertThrows(CancellationException.class, () -> LiteralText.scan("a".repeat(100000), a, 1, () -> true));
        int[] checkpoints = {0};
        assertThrows(CancellationException.class, () -> LiteralText.replace("a".repeat(10000), a, "bb", 10000,
                20000, () -> ++checkpoints[0] > 3));
    }
}
