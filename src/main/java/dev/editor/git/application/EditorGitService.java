package dev.editor.git.application;

import dev.editor.git.domain.*;
import dev.editor.platform.text.domain.NormalizedText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Window-owned acquisition/cache. One status subscription, no document ownership or view polling. */
public final class EditorGitService implements AutoCloseable {
    public record Configuration(int cacheBytes, int entries, int requests, int views, int concurrent) {
        public Configuration {
            if (cacheBytes < 1 || entries < 1 || requests < 1 || views < 1 || concurrent < 1) throw new IllegalArgumentException("Invalid inspection limits");
        }
        public static Configuration defaults() { return new Configuration(16 * 1024 * 1024, 64, 64, 256, 2); }
    }
    public interface Acquisition<T> extends AutoCloseable {
        CompletableFuture<T> result();
        @Override void close();
    }
    private record Key(RepositoryLocation repository, String commit, String path, boolean blame) { }
    private record Cached(Object value, int cost) { }
    private final class Job {
        final CompletableFuture<Object> result = new CompletableFuture<>();
        Future<?> task; int users;
    }
    private final GitRepository repository;
    private final ExecutorService workers;
    private final Executor notifications;
    private final Consumer<Throwable> report;
    private final Configuration limits;
    private final Semaphore concurrency;
    private final AutoCloseable statusSubscription;
    private final Map<Key, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, Job> jobs = new HashMap<>();
    private final List<Consumer<ProjectGitStatus>> listeners = new ArrayList<>();
    private ProjectGitStatus status = ProjectGitStatus.empty(null, ProjectGitStatus.Phase.NO_REPOSITORY, "No project open");
    private Map<String, GitChange> changedFiles = Map.of();
    private int cachedBytes;
    private boolean closed;

    public EditorGitService(GitRepository repository, ProjectGitStatusService statuses, ExecutorService workers,
                            Executor notifications, Configuration limits, Consumer<Throwable> report) {
        this.repository = Objects.requireNonNull(repository); this.workers = Objects.requireNonNull(workers);
        this.notifications = Objects.requireNonNull(notifications); this.limits = Objects.requireNonNull(limits);
        this.report = Objects.requireNonNull(report); concurrency = new Semaphore(limits.concurrent(), true);
        statusSubscription = statuses.subscribe(this::changed);
    }
    public synchronized ProjectGitStatus status() { return status; }
    public synchronized GitChange change(String path) { return changedFiles.get(path); }
    public AutoCloseable observe(Consumer<ProjectGitStatus> consumer) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Editor inspection closed");
            if (listeners.size() >= limits.views()) {
                ProjectGitStatus unavailable = ProjectGitStatus.empty(status.project(), ProjectGitStatus.Phase.UNAVAILABLE, "Editor inspection view limit reached");
                var active = new java.util.concurrent.atomic.AtomicBoolean(true);
                notifications.execute(() -> { if (active.get()) consumer.accept(unavailable); });
                return () -> active.set(false);
            }
            listeners.add(consumer);
        }
        // Snapshot replay is synchronous; callers install on the explicitly configured UI dispatcher.
        consumer.accept(status());
        return () -> { synchronized (this) { listeners.remove(consumer); } };
    }
    private void changed(ProjectGitStatus next) {
        List<Consumer<ProjectGitStatus>> copy;
        synchronized (this) {
            if (closed) return;
            if (!Objects.equals(status.project(), next.project()) || !Objects.equals(status.location(), next.location())) {
                cache.clear(); cachedBytes = 0;
                for (Job job : List.copyOf(jobs.values())) { if (job.task != null) job.task.cancel(true); job.result.cancel(false); }
                jobs.clear();
            }
            Map<String, GitChange> paths = new HashMap<>();
            if (next.local() != null) for (GitChange change : next.local().changes()) paths.put(change.path(), change);
            changedFiles = Map.copyOf(paths);
            status = next; copy = List.copyOf(listeners);
        }
        for (Consumer<ProjectGitStatus> consumer : copy) {
            synchronized (this) { if (closed || !listeners.contains(consumer)) continue; }
            try { consumer.accept(next); } catch (RuntimeException error) { reportFailure(error); }
        }
    }
    public Acquisition<EditorBaseline> baseline(RepositoryLocation location, String commit, String path) {
        return acquire(new Key(location, commit, path, false), () -> {
            GitFileRevision file = commit.isEmpty() ? new GitFileRevision("", path, GitContent.empty()) : repository.fileRevision(location, commit, path);
            if (!file.content().comparable()) throw new IOException(file.content().explanation());
            NormalizedText source = NormalizedText.content(file.content().text(), file.content().byteOrderMark()); requireSize(source.text());
            return new EditorBaseline(location, commit, file, source);
        }, value -> 512 + 2 * (value.source().text().length() + value.file().content().text().length() + value.file().content().metadata().length() + value.file().content().explanation().length()));
    }
    public Acquisition<List<GitBlameLine>> blame(EditorBaseline baseline) {
        return acquire(new Key(baseline.repository(), baseline.commitId(), baseline.file().path(), true), () -> {
            if (!baseline.file().present()) throw new IOException("This file has no committed lines");
            List<GitBlameLine> lines = repository.blame(baseline.repository(), baseline.commitId(), baseline.file().path());
            int count = lineCount(baseline.source().text());
            if (lines.size() != count) throw new IOException("Blame does not match the pinned source");
            return List.copyOf(lines);
        }, lines -> lines.stream().mapToInt(line -> 96 + 2 * (line.author().length() + line.subject().length() + line.originalPath().length() + line.commitId().length())).sum());
    }
    /** Called off the UI thread immediately before a requested editor revert. */
    public boolean validate(EditorBaseline baseline, String currentPath) throws IOException {
        RepositoryLocation current = repository.locate(baseline.repository().root());
        if (!current.equals(baseline.repository())) return false;
        LocalGitStatus local = repository.status(current);
        return local.head().equals(baseline.commitId()) && local.changes().stream().noneMatch(change -> change.path().equals(currentPath) && change.unmerged());
    }
    @SuppressWarnings("unchecked")
    private synchronized <T> Acquisition<T> acquire(Key key, Query<T> query, ToIntFunction<T> cost) {
        if (closed) return completed(CompletableFuture.failedFuture(new IOException("Editor inspection closed")));
        Cached cached = cache.get(key);
        if (cached != null) return completed(CompletableFuture.completedFuture((T)cached.value()));
        Job job = jobs.get(key);
        if (job == null) {
            if (jobs.size() >= limits.requests()) return completed(CompletableFuture.failedFuture(new IOException("Editor inspection request limit reached")));
            job = new Job(); jobs.put(key, job);
        }
        Job requested = job; requested.users++;
        if (requested.task == null) requested.task = workers.submit(() -> {
            boolean acquired = false;
            try {
                concurrency.acquire(); acquired = true;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                T value = query.get(); int bytes = cost.applyAsInt(value);
                synchronized (this) {
                    if (closed || jobs.get(key) != requested || requested.users == 0) return;
                    if (bytes <= limits.cacheBytes()) {
                        while (!cache.isEmpty() && (cache.size() >= limits.entries() || cachedBytes + bytes > limits.cacheBytes())) {
                            Key first = cache.keySet().iterator().next(); cachedBytes -= cache.remove(first).cost();
                        }
                        cache.put(key, new Cached(value, bytes)); cachedBytes += bytes;
                    }
                }
                requested.result.complete(value);
            } catch (Exception error) { requested.result.completeExceptionally(error); }
            finally {
                if (acquired) concurrency.release();
                synchronized (this) { jobs.remove(key, requested); }
            }
        });
        return new Acquisition<>() {
            private boolean released;
            @Override public CompletableFuture<T> result() { return requested.result.thenApply(value -> (T)value); }
            @Override public void close() {
                synchronized (EditorGitService.this) {
                    if (released) return; released = true;
                    if (--requested.users == 0 && !requested.result.isDone()) {
                        jobs.remove(key, requested); requested.task.cancel(true); requested.result.cancel(false);
                    }
                }
            }
        };
    }
    private static <T> Acquisition<T> completed(CompletableFuture<T> result) {
        return new Acquisition<>() { public CompletableFuture<T> result() { return result; } public void close() { } };
    }
    public void dispatch(Runnable task) { notifications.execute(task); }
    public static void requireSize(String text) throws IOException {
        if (text.length() > 2 * 1024 * 1024 || text.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024 || lineCount(text) > 20000)
            throw new IOException("Editor Git inspection is limited to 2 MiB and 20,000 source lines");
    }
    private static int lineCount(String text) {
        if (text.isEmpty()) return 0;
        int count = 0; for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return text.endsWith("\n") ? count : count + 1;
    }
    private void reportFailure(Throwable error) { try { report.accept(error); } catch (RuntimeException ignored) { /* Diagnostics must not stop independent views. */ } }
    @Override public void close() {
        synchronized (this) {
            if (closed) return; closed = true;
            for (Job job : List.copyOf(jobs.values())) { job.task.cancel(true); job.result.cancel(false); }
            jobs.clear(); cache.clear(); cachedBytes = 0; changedFiles = Map.of(); listeners.clear();
        }
        try { statusSubscription.close(); } catch (Exception error) { reportFailure(error); }
    }
    @FunctionalInterface private interface Query<T> { T get() throws Exception; }
}
