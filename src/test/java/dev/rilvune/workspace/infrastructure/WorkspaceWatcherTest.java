package dev.rilvune.workspace.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
}
