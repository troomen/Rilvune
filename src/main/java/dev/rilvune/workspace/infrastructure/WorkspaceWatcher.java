package dev.rilvune.workspace.infrastructure;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
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
    private Set<Path> requested = Set.of();
    private long revision;
    private boolean closed;

    public WorkspaceWatcher(Executor executor, Consumer<Set<Path>> changes, Consumer<IOException> errors) throws IOException {
        this.service = FileSystems.getDefault().newWatchService();
        this.changes = changes;
        this.errors = errors;
        executor.execute(this::consumeEvents);
    }

    public synchronized void watch(Set<Path> requestedDirectories) throws IOException {
        watch(requestedDirectories, revision + 1);
    }

    /** Callers dispatching updates asynchronously assign revisions in UI/request order. */
    public synchronized void watch(Set<Path> requestedDirectories, long requestedRevision) throws IOException {
        if (closed) return;
        if (requestedRevision <= revision) return;
        revision = requestedRevision;
        Set<Path> directories = new HashSet<>();
        for (Path directory : requestedDirectories) directories.add(directory.toAbsolutePath().normalize());
        // Retain interest in temporarily missing paths. Their watched parents report
        // recreation, at which point a new key must be installed for the new directory.
        requested = Set.copyOf(directories);
        reconcileRegistrations();
    }

    private synchronized void reconcileRegistrations() throws IOException {
        if (closed) return;
        registrations.entrySet().removeIf(entry -> {
            if (requested.contains(entry.getKey()) && entry.getValue().isValid()) return false;
            entry.getValue().cancel();
            return true;
        });
        IOException failure = null;
        for (Path directory : requested) {
            if (!registrations.containsKey(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    registrations.put(directory, directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
                } catch (NoSuchFileException | NotDirectoryException disappeared) {
                    // A directory can be replaced between the check and registration.
                } catch (IOException unavailable) {
                    if (failure == null) failure = unavailable; else failure.addSuppressed(unavailable);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void consumeEvents() {
        try {
            while (true) {
                WatchKey first = service.take();
                Set<Path> changed = new HashSet<>();
                consume(first, changed);
                for (WatchKey next; (next = service.poll()) != null;) consume(next, changed);
                // Repair invalidated keys before notifying consumers, even if the tree's
                // expanded paths and the parent directory listing have not changed.
                try { reconcileRegistrations(); } catch (IOException unavailable) { errors.accept(unavailable); }
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

    private synchronized void consume(WatchKey key, Set<Path> changed) {
        Path directory = ((Path) key.watchable()).toAbsolutePath().normalize();
        if (registrations.get(directory) != key) { key.cancel(); return; }
        boolean events = !key.pollEvents().isEmpty();
        boolean valid = key.reset();
        if (events || !valid) changed.add(directory);
        if (!valid) {
            registrations.remove(directory, key);
            Path parent = directory.getParent();
            if (parent != null && requested.contains(parent)) changed.add(parent);
        }
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        service.close();
        registrations.clear();
        requested = Set.of();
    }
}
