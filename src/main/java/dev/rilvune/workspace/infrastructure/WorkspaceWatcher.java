package dev.rilvune.workspace.infrastructure;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Watches only explicitly supplied directories; callbacks run off the EDT. */
public final class WorkspaceWatcher implements AutoCloseable {
    private final WatchService service;
    private final Consumer<Set<Path>> changes;
    private final Consumer<IOException> errors;
    private final Map<Path, WatchKey> registrations = new HashMap<>();
    private boolean closed;

    public WorkspaceWatcher(Executor executor, Consumer<Set<Path>> changes, Consumer<IOException> errors) throws IOException {
        this.service = FileSystems.getDefault().newWatchService();
        this.changes = changes;
        this.errors = errors;
        executor.execute(this::consumeEvents);
    }

    public synchronized void watch(Set<Path> requestedDirectories) throws IOException {
        if (closed) return;
        Set<Path> directories = new HashSet<>();
        for (Path requested : requestedDirectories) {
            Path directory = requested.toAbsolutePath().normalize();
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) directories.add(directory);
        }
        registrations.entrySet().removeIf(entry -> {
            if (directories.contains(entry.getKey()) && entry.getValue().isValid()) return false;
            entry.getValue().cancel();
            return true;
        });
        for (Path directory : directories) {
            if (!registrations.containsKey(directory)) {
                registrations.put(directory, directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
            }
        }
    }

    private void consumeEvents() {
        try {
            while (true) {
                WatchKey first = service.take();
                Set<Path> changed = new HashSet<>();
                consume(first, changed);
                for (WatchKey next; (next = service.poll()) != null;) consume(next, changed);
                if (!changed.isEmpty()) changes.accept(Set.copyOf(changed));
            }
        } catch (ClosedWatchServiceException ignored) {
            // Explicit close wakes the blocking consumer.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            errors.accept(new IOException("Filesystem watcher stopped", failure));
        }
    }

    private void consume(WatchKey key, Set<Path> changed) {
        if (!key.pollEvents().isEmpty()) changed.add(((Path) key.watchable()).toAbsolutePath().normalize());
        if (!key.reset()) changed.add(((Path) key.watchable()).toAbsolutePath().normalize());
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        service.close();
        registrations.clear();
    }
}
