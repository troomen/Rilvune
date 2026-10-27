package dev.editor.document.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Ordered document I/O. Rejected dispatch fails every stranded task, outside the queue monitor. */
final class SerialExecutor {
    private record Task(Runnable action, Consumer<RuntimeException> rejected, AtomicBoolean claimed) { }
    private final Executor executor;
    private final Queue<Task> tasks = new ArrayDeque<>();
    private boolean running;

    SerialExecutor(Executor executor) { this.executor = executor; }

    void submit(Runnable action, Consumer<RuntimeException> rejected) {
        boolean start;
        synchronized (this) {
            tasks.add(new Task(action, rejected, new AtomicBoolean()));
            start = !running;
            running = true;
        }
        if (start) dispatch();
    }

    private void dispatch() {
        Task task;
        synchronized (this) {
            task = tasks.poll();
            if (task == null) { running = false; return; }
        }
        try {
            executor.execute(() -> {
                if (!task.claimed().compareAndSet(false, true)) return;
                try { task.action().run(); }
                finally {
                    try { dispatch(); }
                    catch (RuntimeException failure) { log(failure); }
                }
            });
        } catch (RuntimeException failure) {
            // An executor throwing after dispatch cannot run the same task after rejection won.
            if (task.claimed().compareAndSet(false, true)) {
                List<Task> stranded;
                synchronized (this) {
                    stranded = new ArrayList<>(tasks); tasks.clear(); running = false;
                }
                reject(task, failure);
                for (Task queued : stranded) reject(queued, failure);
            }
            throw failure;
        }
    }
    private static void reject(Task task, RuntimeException failure) {
        try { task.rejected().accept(failure); }
        catch (RuntimeException observerFailure) { log(observerFailure); }
    }
    private static void log(RuntimeException failure) {
        System.getLogger(SerialExecutor.class.getName()).log(System.Logger.Level.WARNING, "Document I/O dispatch failed", failure);
    }
}
