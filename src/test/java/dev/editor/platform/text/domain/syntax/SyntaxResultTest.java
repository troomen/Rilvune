package dev.editor.platform.text.domain.syntax;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SyntaxResultTest {
    private final SyntaxLimits limits = new SyntaxLimits(100, 10, 20, 2, 1, 10, 10, 10000, Duration.ZERO, Duration.ofSeconds(1));
    private SyntaxWorkBudget budget() { return new SyntaxWorkBudget(limits, () -> false, () -> 0); }
    @Test void sparseSpansCoalesceAndLookupUsesExclusiveUtf16Bounds() {
        var builder = SyntaxResult.builder(new SyntaxInput(FixtureSyntax.ID, "paint 42"), budget());
        builder.add(0, 2, SyntaxKind.KEYWORD); builder.add(2, 5, SyntaxKind.KEYWORD); builder.add(6, 8, SyntaxKind.NUMBER);
        var result = builder.build(); assertEquals(2, result.size()); assertEquals(5, result.end(0));
        assertEquals(0, result.firstIntersecting(4)); assertEquals(1, result.firstIntersecting(5)); assertEquals(2, result.firstIntersecting(8));
        assertThrows(IllegalStateException.class, builder::build);
    }
    @Test void invalidBoundsNewlinesOverlapsAndSplitSurrogatePairsAreRejected() {
        var input = new SyntaxInput(FixtureSyntax.ID, "a\n😀z");
        assertThrows(SyntaxFailure.class, () -> SyntaxResult.builder(input, budget()).add(0, 2, SyntaxKind.STRING));
        assertThrows(SyntaxFailure.class, () -> SyntaxResult.builder(input, budget()).add(2, 3, SyntaxKind.STRING));
        var builder = SyntaxResult.builder(input, budget()); builder.add(2, 4, SyntaxKind.STRING);
        assertThrows(SyntaxFailure.class, () -> builder.add(3, 5, SyntaxKind.NUMBER));
        assertDoesNotThrow(() -> SyntaxResult.builder(new SyntaxInput(FixtureSyntax.ID, "\uD800"), budget()).add(0, 1, SyntaxKind.DEFAULT));
    }
    @Test void cancellationDeadlineAndAllInputSpanLimitsFailBeforeUnboundedWork() {
        AtomicBoolean stop = new AtomicBoolean(); AtomicLong time = new AtomicLong();
        var budget = new SyntaxWorkBudget(limits, stop::get, time::get);
        stop.set(true); assertEquals(SyntaxFailure.Reason.CANCELLED, assertThrows(SyntaxFailure.class, budget::check).reason());
        stop.set(false); time.set(Duration.ofSeconds(1).toNanos()); assertEquals(SyntaxFailure.Reason.LIMIT, assertThrows(SyntaxFailure.class, budget::check).reason());
        assertThrows(SyntaxFailure.class, () -> budget().checkInput("x".repeat(101)));
        assertThrows(SyntaxFailure.class, () -> budget().checkInput("x".repeat(21)));
        assertThrows(SyntaxFailure.class, () -> budget().checkInput("\n".repeat(10)));
        var builder = SyntaxResult.builder(new SyntaxInput(FixtureSyntax.ID, "a b c"), budget());
        builder.add(0, 1, SyntaxKind.KEYWORD); builder.add(2, 3, SyntaxKind.STRING);
        assertThrows(SyntaxFailure.class, () -> builder.add(4, 5, SyntaxKind.NUMBER));
    }
}
