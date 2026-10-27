package dev.editor.document.application;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class SerialExecutorTest {
    @Test void rejectionOfNextDispatchFailsStrandedTasksAndAllowsRetry() {
        Queue<Runnable> workers = new ArrayDeque<>();
        List<Integer> ran = new ArrayList<>(), failed = new ArrayList<>();
        boolean[] reject = {false};
        var serial = new SerialExecutor(task -> { if (reject[0]) throw new RejectedExecutionException(); workers.add(task); });
        serial.submit(() -> ran.add(1), failure -> failed.add(1));
        serial.submit(() -> ran.add(2), failure -> failed.add(2));
        serial.submit(() -> ran.add(3), failure -> failed.add(3));
        reject[0] = true; workers.remove().run();
        assertEquals(List.of(1), ran); assertEquals(List.of(2, 3), failed); assertTrue(workers.isEmpty());
        reject[0] = false; serial.submit(() -> ran.add(4), failure -> fail()); workers.remove().run();
        assertEquals(List.of(1, 4), ran);
    }
    @Test void firstRejectionCannotRunLaterEvenIfAnExecutorQueuedBeforeThrowing() {
        Queue<Runnable> workers = new ArrayDeque<>();
        boolean[] ran = {false}, rejected = {false};
        var serial = new SerialExecutor(task -> { workers.add(task); throw new RejectedExecutionException(); });
        assertThrows(RejectedExecutionException.class, () -> serial.submit(() -> ran[0] = true, failure -> rejected[0] = true));
        workers.remove().run(); assertFalse(ran[0]); assertTrue(rejected[0]);
    }
}
