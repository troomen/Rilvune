package dev.editor.diff.ui;

import dev.editor.diff.domain.DiffOptions;
import dev.editor.diff.domain.TextSnapshot;
import dev.editor.diff.infrastructure.DiffLimits;
import dev.editor.diff.infrastructure.MyersDiffEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedTextTest {
    @Test void projectionIncludesBothExactVersionsAndExplicitFinalNewlineMarkers() {
        var diff = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("a", 0, "same\nold"),
                new TextSnapshot("b", 0, "same\nnew\n"), DiffOptions.exact(), () -> false);
        var projection = UnifiedText.render(diff);
        assertEquals(" same\n-old\n\\ No newline at end of file\n+new\n", projection.text());
        assertEquals(java.util.List.of(6), projection.hunkOffsets());
        assertEquals(java.util.List.of("old", "new"), projection.changes().stream().filter(change -> change.kind() == dev.editor.diff.domain.DiffKind.CHANGED)
                .map(change -> projection.text().substring(change.start(), change.end())).toList());
        assertEquals(java.util.List.of("\n"), projection.changes().stream().filter(change -> change.kind() == dev.editor.diff.domain.DiffKind.ADDED)
                .map(change -> projection.text().substring(change.start(), change.end())).toList());
    }
    @Test void oversizeProjectionExplainsItsLimitWithoutPretendingToShowAllText() {
        String text = "large line content\n".repeat(60_000);
        var diff = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("a", 0, text), new TextSnapshot("b", 0, text), DiffOptions.exact(), () -> false);
        var projection = UnifiedText.render(diff);
        assertTrue(projection.text().contains("too large")); assertTrue(projection.hunkOffsets().isEmpty()); assertTrue(projection.changes().isEmpty());
    }
}
