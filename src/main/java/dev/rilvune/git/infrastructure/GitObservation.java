package dev.rilvune.git.infrastructure;

import dev.rilvune.git.domain.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Watch the working tree and actual worktree/common metadata, without following symlinks. */
public final class GitObservation implements RepositoryObservation {
    private final ExecutorService workers;
    public GitObservation(ExecutorService workers) { this.workers = workers; }
    @Override public AutoCloseable observe(RepositoryLocation repository, Runnable changed) throws IOException {
        WatchService watch = FileSystems.getDefault().newWatchService(); AtomicBoolean closed = new AtomicBoolean();
        try {
            Set<Path> seen = new HashSet<>();
            for (Path root : List.of(repository.root(), repository.gitDirectory(), repository.commonDirectory())) register(root, watch, seen);
            Future<?> task = workers.submit(() -> {
                try {
                    while (!closed.get()) {
                        WatchKey key = watch.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && event.context() instanceof Path path) {
                                try { register(((Path) key.watchable()).resolve(path), watch, seen); } catch (IOException ignored) { /* reconciliation recovers dropped watches */ }
                            }
                        }
                        key.reset(); changed.run();
                    }
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                catch (ClosedWatchServiceException ignored) { }
            });
            return () -> { closed.set(true); watch.close(); task.cancel(true); };
        } catch (IOException | RuntimeException failure) { watch.close(); throw failure; }
    }
    private void register(Path root, WatchService watch, Set<Path> seen) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                if (seen.size() >= 12000) return FileVisitResult.SKIP_SUBTREE;
                if (!seen.add(directory)) return FileVisitResult.SKIP_SUBTREE;
                // Objects never change an already-loaded immutable snapshot; refs/index/HEAD do.
                if (directory.getFileName().toString().equals("objects") && Files.isDirectory(directory.resolve("pack"))) return FileVisitResult.SKIP_SUBTREE;
                directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) { return FileVisitResult.CONTINUE; }
        });
    }
}
