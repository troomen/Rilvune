package dev.editor.git.application;

import dev.editor.diff.domain.*;
import dev.editor.diff.infrastructure.*;
import dev.editor.git.domain.FileHistoryQuery.Lines;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorAttributionTest {
    @Test void selectionMapsAcrossLocalInsertionsButRejectsChangedOrDiscontinuousLines() {
        var engine = new MyersDiffEngine(DiffLimits.interactive());
        DiffResult result = engine.compare(new TextSnapshot("head", 0, "a\nb\nc\nd\ne\n"),
                new TextSnapshot("editor", 1, "new\na\nchanged\nc\ne\n"), DiffOptions.exact(), () -> false);
        assertEquals(new Lines(0, 1), EditorAttribution.committedRange(result, new Lines(1, 2)));
        assertEquals(new Lines(2, 3), EditorAttribution.committedRange(result, new Lines(3, 4)));
        assertThrows(IllegalArgumentException.class, () -> EditorAttribution.committedRange(result, new Lines(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> EditorAttribution.committedRange(result, new Lines(1, 3)));
        assertThrows(IllegalArgumentException.class, () -> EditorAttribution.committedRange(result, new Lines(3, 5)));
        assertThrows(IllegalArgumentException.class, () -> EditorAttribution.committedRange(result, new Lines(5, 6)));
    }
    @Test void attributionIsLimitedToProvenUnchangedLinesAcrossInsertionsEditsAndDeletions() {
        var engine = new MyersDiffEngine(DiffLimits.interactive());
        DiffResult result = engine.compare(new TextSnapshot("head", 0, "a\nb\nc\nd\ne\n"), new TextSnapshot("editor", 1, "new\na\nchanged\nc\ne\n"), DiffOptions.exact(), () -> false);
        assertArrayEquals(new int[]{-1, 0, -1, 2, 4}, EditorAttribution.unchangedLines(result));
        DiffResult identical = engine.compare(result.left(), result.left(), DiffOptions.exact(), () -> false);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, EditorAttribution.unchangedLines(identical));
    }
}
