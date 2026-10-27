package dev.rilvune.git.application;

import dev.rilvune.git.domain.*;
import dev.rilvune.diff.domain.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Per-workspace request ownership. Cancellation + generations protect every asynchronous result. */
public final class GitWorkspaceService implements AutoCloseable {
    public enum Request { CONTEXT, REFS, HISTORY, SELECTION, CONTENT }
    public record Comparison(GitContent left, GitContent right) { }
    public record FileHistoryContext(RepositoryLocation repository, FileHistoryQuery query) { }
    private final GitRepository repository;
    private final ExecutorService workers;
    private final Executor notifications;
    private final Map<Request, Future<?>> pending = new EnumMap<>(Request.class);
    private final Map<Request, Long> versions = new EnumMap<>(Request.class);
    private long generation;
    private RepositoryLocation location;
    private boolean closed;
    public GitWorkspaceService(GitRepository repository, ExecutorService workers, Executor notifications) {
        this.repository = repository; this.workers = workers; this.notifications = notifications;
    }
    public synchronized void bind(RepositoryLocation location) {
        if (java.util.Objects.equals(this.location, location)) return;
        reset(); this.location = location;
    }
    public void references(Consumer<List<GitRef>> loaded, Consumer<String> failed) {
        RepositoryLocation current = location(); request(Request.REFS, () -> repository.references(current), loaded, failed);
    }
    public void history(HistoryQuery query, int offset, int count, Consumer<HistoryPage> loaded, Consumer<String> failed) {
        RepositoryLocation current = location(); request(Request.HISTORY, () -> repository.history(current, query, offset, count), loaded, failed);
    }
    public void fileHistoryContext(Path project, Path file, String editorText, FileHistoryQuery.Lines selection, DiffEngine engine,
                                   Consumer<FileHistoryContext> loaded, Consumer<String> failed) {
        pause();
        request(Request.CONTEXT, () -> {
            RepositoryLocation current = repository.locate(project);
            Path absolute = file.toAbsolutePath().normalize();
            if (!absolute.startsWith(current.root())) throw new IOException("File is outside the project repository");
            String path = current.root().relativize(absolute).toString();
            RepositoryPaths.resolve(current.root(), path);
            LocalGitStatus status = repository.status(current);
            if (status.head().isEmpty()) throw new IOException("This repository has no commits yet");
            GitChange local = status.changes().stream().filter(change -> change.path().equals(path)).findFirst().orElse(null);
            String historical = local == null ? path : local.oldPath();
            FileHistoryQuery.Lines committed = selection;
            if (selection != null) {
                if (local != null && (local.untracked() || local.unmerged())) throw new IOException("Selected lines have no unambiguous committed version; use file history");
                GitFileRevision baseline = repository.fileRevision(current, status.head(), historical);
                if (!baseline.present() || !baseline.content().comparable()) throw new IOException("Selected lines have no committed text version; use file history");
                DiffResult diff = engine.compare(new TextSnapshot("history:head", 0, baseline.content().text()),
                        new TextSnapshot("history:editor", 0, Objects.requireNonNull(editorText)), DiffOptions.exact(), Thread.currentThread()::isInterrupted);
                committed = EditorAttribution.committedRange(diff, selection);
            }
            return new FileHistoryContext(current, new FileHistoryQuery(status.head(), historical, committed));
        }, context -> { bind(context.repository()); loaded.accept(context); }, failed);
    }
    public void fileHistory(FileHistoryQuery query, int offset, int count, Consumer<FileHistoryPage> loaded, Consumer<String> failed) {
        RepositoryLocation current = location();
        request(Request.HISTORY, () -> repository.fileHistory(current, query, offset, count), loaded, failed);
    }
    public void commit(String id, Consumer<GitCommit> loaded, Consumer<String> failed) { RepositoryLocation current = location(); request(Request.SELECTION, () -> repository.commit(current, id), loaded, failed); }
    public void jump(String revision, Consumer<GitCommit> loaded, Consumer<String> failed) { RepositoryLocation current = location(); request(Request.SELECTION, () -> repository.commit(current, repository.resolve(current, revision)), loaded, failed); }
    public void changes(GitSource left, GitSource right, Consumer<List<GitChange>> loaded, Consumer<String> failed) { RepositoryLocation current = location(); request(Request.SELECTION, () -> repository.changes(current, left, right), loaded, failed); }
    public void comparison(GitSource left, GitSource right, GitChange path, Consumer<Comparison> loaded, Consumer<String> failed) {
        RepositoryLocation current = location(); request(Request.CONTENT, () -> {
            List<GitChange> paths = List.of(path);
            boolean volatileContent = left.volatileContent() || right.volatileContent();
            String before = volatileContent ? repository.fingerprint(current, paths) : "";
            GitContent first = repository.content(current, left, left.kind() == GitSource.Kind.INDEX ? path.path() : path.oldPath());
            GitContent second = repository.content(current, right, path.path());
            if (volatileContent && !before.equals(repository.fingerprint(current, paths))) throw new IOException("Source changed while loading. Updating on the next refresh.");
            return new Comparison(first, second);
        }, loaded, failed);
    }
    public synchronized void pause() { cancelAll(); }
    public synchronized void cancel(Request request) { versions.merge(request, 1L, Long::sum); Future<?> task = pending.remove(request); if (task != null) task.cancel(true); }
    private synchronized RepositoryLocation location() { if (location == null) throw new IllegalStateException("No repository loaded"); return location; }
    private synchronized <T> void request(Request kind, Query<T> query, Consumer<T> loaded, Consumer<String> failed) {
        cancel(kind); if (closed) return;
        long epoch = generation, version = versions.get(kind);
        pending.put(kind, workers.submit(() -> {
            try {
                T value = query.get();
                notifications.execute(() -> { synchronized (this) { if (!current(kind, epoch, version)) return; pending.remove(kind); } loaded.accept(value); });
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                notifications.execute(() -> { synchronized (this) { if (!current(kind, epoch, version)) return; pending.remove(kind); } failed.accept(message(failure)); });
            }
        }));
    }
    private boolean current(Request kind, long epoch, long version) { return !closed && generation == epoch && versions.getOrDefault(kind, 0L) == version; }
    private void cancelAll() { for (Request request : Request.values()) cancel(request); }
    private void reset() { generation++; cancelAll(); location = null; }
    private String message(Exception failure) { return failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
    @Override public synchronized void close() { reset(); closed = true; }
    @FunctionalInterface private interface Query<T> { T get() throws Exception; }
}
