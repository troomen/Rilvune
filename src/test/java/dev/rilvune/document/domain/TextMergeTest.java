package dev.rilvune.document.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextMergeTest {
    @Test void mergesIndependentLinesAndSameLineNonoverlappingEdits() {
        assertEquals("FIRST\nsecond\nTHIRD\n", TextMerge.merge("first\nsecond\nthird\n",
                "FIRST\nsecond\nthird\n", "first\nsecond\nTHIRD\n").orElseThrow());
        assertEquals("ABC def XYZ", TextMerge.merge("abc def xyz", "ABC def xyz", "abc def XYZ").orElseThrow());
    }

    @Test void doesNotChooseASideWhenReplacementsOrInsertionsCollide() {
        assertTrue(TextMerge.merge("abc", "axc", "ayc").isEmpty());
        assertTrue(TextMerge.merge("ab", "axb", "ayb").isEmpty());
        assertEquals("axb", TextMerge.merge("ab", "axb", "axb").orElseThrow());
    }

    @Test void retainsMultipleChangesOnEitherSide() {
        assertEquals("ONE\nTWO\nTHREE\nFOUR\n", TextMerge.merge("one\ntwo\nthree\nfour\n",
                "ONE\ntwo\nTHREE\nfour\n", "one\nTWO\nthree\nFOUR\n").orElseThrow());
    }
}
