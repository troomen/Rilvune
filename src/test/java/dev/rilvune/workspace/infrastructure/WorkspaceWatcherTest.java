package dev.rilvune.workspace.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceWatcherTest {
    @TempDir Path temporary;

    @Test void watchesOnlyExplicitDirectoriesAndReportsTheBranchForRefresh() throws Exception {
        Path branch = Files.createDirectory(temporary.resolve("branch"));
        CompletableFuture<Set<Path>> changed = new CompletableFuture<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var watcher = new WorkspaceWatcher(executor, changed::complete, changed::completeExceptionally)) {
            watcher.watch(Set.of(branch));
            Files.writeString(branch.resolve("external.txt"), "created externally");
            assertTrue(changed.get(5, TimeUnit.SECONDS).contains(branch));
            watcher.watch(Set.of());
        }
    }

    @Test void deletedAndRecreatedExpandedDirectoryIsWatchedWithoutAnotherExpansionEvent() throws Exception {
        Path branch = Files.createDirectory(temporary.resolve("branch"));
        var changed = new LinkedBlockingQueue<Set<Path>>();
        var deleted = new AtomicBoolean();
        var resume = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var watcher = new WorkspaceWatcher(executor, paths -> {
                 changed.add(paths);
                 if (paths.contains(branch) && deleted.compareAndSet(false, true)) {
                     try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                     catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                 }
             }, failure -> { throw new AssertionError(failure); })) {
            watcher.watch(Set.of(temporary, branch));
            try {
                Files.delete(branch);
                awaitChange(changed, branch);
                changed.clear();
                // Keep consumption paused until recreation is visible. Any queued parent
                // event then repairs the key before its callback is delivered.
                Files.createDirectory(branch);
            } finally { resume.countDown(); }
            awaitChange(changed, temporary);
            changed.clear();
            Files.writeString(branch.resolve("external.txt"), "created in the replacement directory");
            awaitChange(changed, branch);
        }
    }

    @Test void delayedOlderWatchRequestCannotUndoTheLatestExpandedDirectories() throws Exception {
        Path branch = Files.createDirectory(temporary.resolve("branch"));
        var changed = new LinkedBlockingQueue<Set<Path>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var watcher = new WorkspaceWatcher(executor, changed::add, failure -> { throw new AssertionError(failure); })) {
            watcher.watch(Set.of(temporary, branch), 2);
            watcher.watch(Set.of(temporary), 1);
            Files.writeString(branch.resolve("external.txt"), "newest expansion is still watched");
            awaitChange(changed, branch);
        }
    }

    private static void awaitChange(LinkedBlockingQueue<Set<Path>> changes, Path directory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Set<Path> changed = changes.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (changed != null && changed.contains(directory)) return;
        }
        org.junit.jupiter.api.Assertions.fail("No filesystem notification for " + directory);
    }
}
