package dev.rilvune.diff.application;

import dev.rilvune.diff.domain.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns one comparison request. Cancellation and stale-result suppression are independent of Swing. */
public final class DiffController implements AutoCloseable {
    private final DiffEngine engine;
    private final ExecutorService workers;
    private final Executor notifications;
    private final Consumer<DiffResult> result;
    private final Consumer<String> failure;
    private final AtomicLong generation = new AtomicLong();
    private Future<?> pending;
    private volatile boolean closed;

    public DiffController(DiffEngine engine, ExecutorService workers, Executor notifications,
                          Consumer<DiffResult> result, Consumer<String> failure) {
        this.engine = engine; this.workers = workers; this.notifications = notifications;
        this.result = result; this.failure = failure;
    }
    public synchronized void invalidate() {
        generation.incrementAndGet();
        if (pending != null) { pending.cancel(true); pending = null; }
    }
    public synchronized void compare(TextSnapshot left, TextSnapshot right, DiffOptions options) {
        invalidate();
        if (closed) return;
        long request = generation.get();
        pending = workers.submit(() -> {
            try {
                DiffResult diff = engine.compare(left, right, options, () -> closed || generation.get() != request);
                notifications.execute(() -> { if (!closed && generation.get() == request) result.accept(diff); });
            } catch (CancellationException ignored) { }
            catch (RuntimeException error) {
                notifications.execute(() -> { if (!closed && generation.get() == request) failure.accept(error.getMessage() == null ? "Comparison failed" : error.getMessage()); });
            }
        });
    }
    @Override public synchronized void close() { closed = true; invalidate(); }
}
