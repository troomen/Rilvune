package dev.editor.git.application;

import dev.editor.git.domain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Project-owned status stream. No UI, static project state, or editable document ownership. */
public final class ProjectGitStatusService implements AutoCloseable {
    public record Configuration(Duration coalesce, Duration reconcile, int pathLimit, int subscriberLimit) {
        public Configuration {
            if (coalesce.isNegative() || reconcile.isZero() || reconcile.isNegative()
                    || pathLimit < 1 || pathLimit > 20000 || subscriberLimit < 1) throw new IllegalArgumentException("Invalid status limits");
        }
        public static Configuration defaults() { return new Configuration(Duration.ofMillis(250), Duration.ofSeconds(3), 20000, 32); }
    }
    private final GitRepository repository;
    private final RepositoryObservation observation;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Executor notifications;
    private final Consumer<Throwable> report;
    private final Configuration configuration;
    private final List<Subscription> subscribers = new ArrayList<>();
    private final ScheduledFuture<?> reconciliation;
    private ScheduledFuture<?> debounce;
    private Future<?> pending;
    private Context context;
    private ProjectGitStatus current = ProjectGitStatus.empty(null, ProjectGitStatus.Phase.NO_REPOSITORY, "No project open");
    private Set<String> candidates = Set.of();
    private long generation, sequence;
    private boolean running, again, closed, candidatesLimited;

    public ProjectGitStatusService(GitRepository repository, RepositoryObservation observation, ExecutorService workers,
                                   ScheduledExecutorService scheduler, Executor notifications, Configuration configuration,
                                   Consumer<Throwable> report) {
        this.repository = Objects.requireNonNull(repository); this.observation = Objects.requireNonNull(observation);
        this.workers = Objects.requireNonNull(workers); this.scheduler = Objects.requireNonNull(scheduler);
        this.notifications = Objects.requireNonNull(notifications); this.configuration = Objects.requireNonNull(configuration);
        this.report = Objects.requireNonNull(report);
        reconciliation = scheduler.scheduleWithFixedDelay(this::refresh, configuration.reconcile().toMillis(), configuration.reconcile().toMillis(), TimeUnit.MILLISECONDS);
    }
    public AutoCloseable subscribe(Consumer<ProjectGitStatus> listener) {
        Subscription subscription;
        ProjectGitStatus snapshot; long epoch, version;
        synchronized (this) {
            requireOpen();
            if (subscribers.size() >= configuration.subscriberLimit()) throw new IllegalStateException("Too many status subscribers");
            subscription = new Subscription(Objects.requireNonNull(listener)); subscribers.add(subscription);
            snapshot = current; epoch = generation; version = sequence;
        }
        deliver(subscription, snapshot, epoch, version);
        return subscription;
    }
    public void project(Path root) {
        Context old;
        synchronized (this) {
            requireOpen(); root = root == null ? null : root.toAbsolutePath().normalize();
            if (Objects.equals(current.project(), root)) return;
            generation++; old = context; cancelRequests(); candidates = Set.of(); candidatesLimited = false;
            context = root == null ? null : new Context(root);
            current = ProjectGitStatus.empty(root, root == null ? ProjectGitStatus.Phase.NO_REPOSITORY : ProjectGitStatus.Phase.LOADING,
                    root == null ? "No project open" : "Loading Git status"); sequence++;
        }
        closeWatch(old); publish(); refresh();
    }
    /** Materialized tree files only. Bounded once here; renderers never issue repository queries. */
    public void observedPaths(Set<Path> paths) {
        synchronized (this) {
            if (closed || context == null) return;
            TreeSet<String> next = new TreeSet<>();
            boolean limited = false;
            for (Path path : paths) {
                Path absolute = path.toAbsolutePath().normalize();
                if (!absolute.startsWith(context.root) || absolute.equals(context.root)) continue;
                String relative = context.root.relativize(absolute).toString();
                if (relative.equals(".git") || relative.startsWith(".git/")) continue;
                next.add(relative);
                if (next.size() > configuration.pathLimit()) { next.pollLast(); limited = true; }
            }
            if (candidates.equals(next) && candidatesLimited == limited) return;
            candidates = Set.copyOf(next); candidatesLimited = limited;
        }
        invalidate();
    }
    public void invalidate() { schedule(configuration.coalesce().toMillis()); }
    public void refresh() { schedule(0); }
    private synchronized void schedule(long delay) {
        if (closed || context == null) return;
        if (running) { again = true; return; }
        if (debounce != null) debounce.cancel(false);
        debounce = scheduler.schedule(this::start, delay, TimeUnit.MILLISECONDS);
    }
    private synchronized void start() {
        if (closed || context == null) return;
        if (running) { again = true; return; }
        running = true; again = false;
        Context requested = context; Set<String> paths = candidates; boolean limited = candidatesLimited;
        pending = workers.submit(() -> {
            ProjectGitStatus loaded;
            try { loaded = load(requested, paths, limited); }
            catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                ProjectGitStatus previous;
                synchronized (this) { previous = current; }
                loaded = previous.available() && Objects.equals(previous.project(), requested.root) && Objects.equals(previous.location(), requested.location)
                        ? new ProjectGitStatus(requested.root, previous.location(), previous.local(), previous.paths(), ProjectGitStatus.Phase.STALE, message(failure))
                        : ProjectGitStatus.empty(requested.root, ProjectGitStatus.Phase.UNAVAILABLE, message(failure));
            }
            ProjectGitStatus result = loaded;
            notifications.execute(() -> complete(requested, result));
        });
    }
    private ProjectGitStatus load(Context requested, Set<String> paths, boolean limited) throws IOException {
        if (!Files.exists(requested.root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            requested.location = null; closeWatch(requested);
            return ProjectGitStatus.empty(requested.root, ProjectGitStatus.Phase.NO_REPOSITORY, "This project has no Git repository");
        }
        RepositoryLocation located;
        try { located = repository.locate(requested.root); }
        catch (IOException failure) { requested.location = null; closeWatch(requested); throw failure; }
        if (!located.equals(requested.location)) { closeWatch(requested); requested.ignored.clear(); requested.ignoreStamp = ""; requested.location = located; }
        if (requested.watch == null) {
            try {
                AutoCloseable watch = observation.observe(located, () -> invalidate(requested));
                synchronized (this) {
                    if (closed || context != requested) { closeResource(watch); return ProjectGitStatus.empty(requested.root, ProjectGitStatus.Phase.UNAVAILABLE, "Project closed"); }
                    requested.watch = watch; requested.watchWarning = "";
                }
            } catch (IOException failure) { requested.watchWarning = "Watch unavailable; periodic checks remain active: " + message(failure); }
        }
        LocalGitStatus local = repository.status(located);
        if (local.changes().size() > configuration.pathLimit()) throw new IOException("Git status exceeds the " + configuration.pathLimit() + " changed-path limit");
        String ignoreStamp = repository.ignoreFingerprint(located, paths);
        if (!ignoreStamp.equals(requested.ignoreStamp)) { requested.ignored.clear(); requested.ignoreStamp = ignoreStamp; }
        requested.ignored.keySet().retainAll(paths);
        List<String> batch = new ArrayList<>(); int bytes = 0;
        for (String path : paths) {
            if (requested.ignored.containsKey(path)) continue;
            int size = path.getBytes(StandardCharsets.UTF_8).length + 1;
            if (!batch.isEmpty() && (batch.size() == 512 || bytes + size > 256 * 1024)) { classify(requested, batch); batch.clear(); bytes = 0; }
            batch.add(path); bytes += size;
        }
        classify(requested, batch);
        if (!ignoreStamp.equals(repository.ignoreFingerprint(located, paths)) || !local.fingerprint().equals(repository.statusFingerprint(located, local.changes())))
            throw new IOException("Git status/ignore inputs changed while loading; retrying");
        Map<Path, GitPathStatus> decorations = new HashMap<>();
        for (Map.Entry<String, Boolean> entry : requested.ignored.entrySet()) if (entry.getValue())
            decorations.put(RepositoryPaths.resolve(located.root(), entry.getKey()), new GitPathStatus(GitPathStatus.Kind.IGNORED, "Git: ignored"));
        for (GitChange change : local.changes()) decorations.put(RepositoryPaths.resolve(located.root(), change.path()), GitPathStatus.from(change));
        return new ProjectGitStatus(requested.root, located, local, decorations, ProjectGitStatus.Phase.READY, requested.watchWarning + (limited ? " Ignored-file classification limited to " + configuration.pathLimit() + " materialized paths; other files remain neutral unless changed." : ""));
    }
    private void classify(Context context, List<String> batch) throws IOException {
        if (batch.isEmpty()) return;
        Set<String> ignored = repository.ignored(context.location, List.copyOf(batch));
        for (String path : batch) context.ignored.put(path, ignored.contains(path));
    }
    private void invalidate(Context requested) { synchronized (this) { if (context != requested || closed) return; } invalidate(); }
    private void complete(Context requested, ProjectGitStatus snapshot) {
        boolean changed, followup;
        synchronized (this) {
            if (closed || context != requested) return;
            pending = null; running = false; followup = again; again = false;
            changed = !current.equals(snapshot);
            if (changed) { current = snapshot; sequence++; }
        }
        if (changed) publish();
        if (followup) invalidate();
    }
    private void publish() {
        List<Subscription> listeners; ProjectGitStatus snapshot; long epoch, version;
        synchronized (this) { listeners = List.copyOf(subscribers); snapshot = current; epoch = generation; version = sequence; }
        for (Subscription listener : listeners) deliver(listener, snapshot, epoch, version);
    }
    private void deliver(Subscription subscription, ProjectGitStatus snapshot, long epoch, long version) {
        notifications.execute(() -> {
            Consumer<ProjectGitStatus> listener;
            synchronized (this) {
                if (closed || epoch != generation || !subscription.active || version <= subscription.delivered) return;
                subscription.delivered = version; listener = subscription.listener;
            }
            try { listener.accept(snapshot); } catch (RuntimeException failure) { reportFailure(failure); }
        });
    }
    private void cancelRequests() {
        if (debounce != null) debounce.cancel(false);
        if (pending != null) pending.cancel(true);
        pending = null; running = false; again = false;
    }
    private void closeWatch(Context context) {
        AutoCloseable watch;
        synchronized (this) { if (context == null) return; watch = context.watch; context.watch = null; }
        if (watch != null) closeResource(watch);
    }
    private void closeResource(AutoCloseable resource) { try { resource.close(); } catch (Exception failure) { reportFailure(failure); } }
    private void reportFailure(Throwable failure) { try { report.accept(failure); } catch (RuntimeException ignored) { /* Diagnostics cannot stop independent subscribers. */ } }
    private String message(Exception failure) { return failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
    private void requireOpen() { if (closed) throw new IllegalStateException("Status service is closed"); }
    @Override public void close() {
        Context previous;
        synchronized (this) {
            if (closed) return; closed = true; generation++; cancelRequests(); reconciliation.cancel(false);
            previous = context; context = null; candidates = Set.of(); current = ProjectGitStatus.empty(null, ProjectGitStatus.Phase.NO_REPOSITORY, "Closed");
            for (Subscription subscription : subscribers) { subscription.active = false; subscription.listener = null; }
            subscribers.clear();
        }
        closeWatch(previous);
    }
    private final class Subscription implements AutoCloseable {
        private Consumer<ProjectGitStatus> listener;
        private long delivered = -1;
        private boolean active = true;
        private Subscription(Consumer<ProjectGitStatus> listener) { this.listener = listener; }
        @Override public void close() { synchronized (ProjectGitStatusService.this) { active = false; listener = null; subscribers.remove(this); } }
    }
    private static final class Context {
        final Path root;
        final Map<String, Boolean> ignored = new HashMap<>();
        volatile RepositoryLocation location;
        AutoCloseable watch;
        String ignoreStamp = "", watchWarning = "";
        Context(Path root) { this.root = root; }
    }
}
