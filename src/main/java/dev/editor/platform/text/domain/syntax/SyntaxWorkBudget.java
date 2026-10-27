package dev.editor.platform.text.domain.syntax;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Cooperative CPU/output budget. It cannot forcibly interrupt a third-party call. */
public final class SyntaxWorkBudget {
    private final SyntaxLimits limits;
    private final BooleanSupplier cancelled;
    private final LongSupplier clock;
    private final long started;

    public SyntaxWorkBudget(SyntaxLimits limits, BooleanSupplier cancelled, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits); this.cancelled = Objects.requireNonNull(cancelled);
        this.clock = Objects.requireNonNull(clock); started = clock.getAsLong();
    }
    public SyntaxLimits limits() { return limits; }
    public void check() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new SyntaxFailure(SyntaxFailure.Reason.CANCELLED, "Syntax work cancelled");
        if (clock.getAsLong() - started >= limits.workTime().toNanos()) throw SyntaxFailure.limit("Syntax work time limit");
    }
    public void checkInput(String text) {
        check();
        if (text.length() > limits.characters()) throw SyntaxFailure.limit("Syntax text size limit");
        int lines = 1, width = 0;
        for (int i = 0; i < text.length(); i++) {
            if ((i & 1023) == 0) check();
            if (text.charAt(i) == '\n') { lines++; width = 0; }
            else if (++width > limits.lineCharacters()) throw SyntaxFailure.limit("Syntax line length limit");
            if (lines > limits.lines()) throw SyntaxFailure.limit("Syntax line count limit");
        }
    }
}
