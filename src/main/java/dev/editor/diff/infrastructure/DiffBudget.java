package dev.editor.diff.infrastructure;

import dev.editor.diff.domain.DiffLimitException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One budget covers the entire request, including nested line, token and code-point comparisons. */
final class DiffBudget {
    private final DiffLimits limits;
    private final BooleanSupplier cancellation;
    private final long deadline;
    private long work;
    DiffBudget(DiffLimits limits, BooleanSupplier cancellation) {
        this.limits = limits; this.cancellation = cancellation;
        deadline = System.nanoTime() + limits.timeout().toNanos();
    }
    boolean cancelled() { check(); return false; }
    void work(long amount) {
        work += amount;
        if (work > limits.maximumWork()) throw new DiffLimitException("Comparison exceeded its work limit");
        if ((work & 1023) == 0 || amount > 1) check();
    }
    void check() {
        if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (System.nanoTime() > deadline) throw new DiffLimitException("Comparison exceeded its time limit");
    }
}
