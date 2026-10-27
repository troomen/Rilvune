package dev.editor.diff.application;

import dev.editor.diff.domain.*;
import dev.editor.diff.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DiffControllerTest {
    @Test void closingSuppressesAnAlreadyQueuedResult() throws Exception {
        var notifications = new ConcurrentLinkedQueue<Runnable>(); CountDownLatch queued = new CountDownLatch(1);
        List<DiffResult> results = new CopyOnWriteArrayList<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var controller = new DiffController(new MyersDiffEngine(DiffLimits.interactive()), workers,
                     notification -> { notifications.add(notification); queued.countDown(); }, results::add, message -> fail(message))) {
            controller.compare(new TextSnapshot("a", 0, "left"), new TextSnapshot("b", 0, "right"), DiffOptions.exact());
            assertTrue(queued.await(5, TimeUnit.SECONDS)); controller.close(); notifications.forEach(Runnable::run);
            assertTrue(results.isEmpty());
        }
    }
    @Test void obsoleteComputationCannotPublishAfterANewerRequestEvenIfItIgnoresCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        DiffEngine delegate = new MyersDiffEngine(DiffLimits.interactive());
        DiffEngine engine = (left, right, options, cancelled) -> {
            if (left.revision() == 1) {
                started.countDown();
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
            }
            return delegate.compare(left, right, options, () -> false);
        };
        List<DiffResult> results = new CopyOnWriteArrayList<>(); CompletableFuture<Void> latest = new CompletableFuture<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var controller = new DiffController(engine, workers, Runnable::run, result -> { results.add(result); latest.complete(null); }, message -> fail(message))) {
            controller.compare(new TextSnapshot("a", 1, "old"), new TextSnapshot("b", 1, "other"), DiffOptions.exact());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            controller.compare(new TextSnapshot("a", 2, "new"), new TextSnapshot("b", 2, "other"), DiffOptions.exact());
            latest.get(5, TimeUnit.SECONDS); release.countDown();
        } finally { release.countDown(); }
        assertEquals(1, results.size()); assertEquals(2, results.getFirst().left().revision());
    }
}
