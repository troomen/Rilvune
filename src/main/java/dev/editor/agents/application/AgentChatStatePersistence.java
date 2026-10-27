package dev.editor.agents.application;

import dev.editor.agents.domain.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Coalesces keystrokes and serializes atomic writes. Project shutdown explicitly flushes this queue. */
final class AgentChatStatePersistence {
    private final AgentChatStateStore store;
    private final Executor workers;
    private final ScheduledExecutorService scheduler;
    private final BiConsumer<Path, Throwable> report;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private ScheduledFuture<?> scheduled;
    private Path project;
    private AgentChatState pending;
    private long sequence, reported;

    AgentChatStatePersistence(AgentChatStateStore store, Executor workers, ScheduledExecutorService scheduler, BiConsumer<Path, Throwable> report) {
        this.store = store; this.workers = workers; this.scheduler = scheduler; this.report = report;
    }
    AgentChatState load(Path project) throws Exception { return store.load(project); }
    synchronized void update(Path project, AgentChatState state) {
        this.project = project; pending = state;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = scheduler.schedule(this::flush, 350, TimeUnit.MILLISECONDS);
    }
    synchronized CompletableFuture<Void> flush() {
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
        if (pending != null) {
            Path target = project; AgentChatState state = pending; pending = null; long version = ++sequence;
            tail = tail.handle((ignored, failure) -> null).thenRunAsync(() -> {
                try { store.save(target, state); } catch (Exception failure) { throw new CompletionException(failure); }
            }, workers);
            tail.whenCompleteAsync((ignored, failure) -> {
                synchronized (this) { if (version < reported) return; reported = version; }
                report.accept(target, failure);
            }, workers);
        }
        return tail.copy();
    }
}
