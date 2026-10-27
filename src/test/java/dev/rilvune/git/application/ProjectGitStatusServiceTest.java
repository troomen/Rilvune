package dev.rilvune.git.application;

import dev.rilvune.git.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectGitStatusServiceTest {
    @TempDir Path temporary;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Queue<Runnable> dispatch = new ConcurrentLinkedQueue<>();
    private final FakeRepository repository = new FakeRepository();
    private final AtomicInteger started = new AtomicInteger(), stopped = new AtomicInteger(), errors = new AtomicInteger();
    private final ProjectGitStatusService service = new ProjectGitStatusService(repository,
            (location, changed) -> { started.incrementAndGet(); return stopped::incrementAndGet; }, workers, scheduler, dispatch::add,
            new ProjectGitStatusService.Configuration(Duration.ofMillis(5), Duration.ofDays(1), 1000, 8), error -> errors.incrementAndGet());
    @AfterEach void close() { service.close(); scheduler.shutdownNow(); workers.shutdownNow(); }
    private Path project(String name) throws IOException { Path root = Files.createDirectory(temporary.resolve(name)); Files.createDirectory(root.resolve(".git")); return root; }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do { Runnable task; while ((task = dispatch.poll()) != null) task.run(); if (condition.getAsBoolean()) return; Thread.sleep(5); } while (System.nanoTime() < until);
        fail("Timed out waiting for shared status");
    }
    @Test void twoSubscribersShareQueriesWatchAndIgnoredCache() throws Exception {
        Path root = project("repo"); List<ProjectGitStatus> one = new ArrayList<>(), two = new ArrayList<>();
        service.subscribe(one::add); service.subscribe(two::add); service.project(root);
        await(() -> !one.isEmpty() && one.getLast().available()); assertEquals(one, two); assertEquals(1, started.get());
        service.observedPaths(Set.of(root.resolve("ignored"), root.resolve("plain")));
        await(() -> one.getLast().paths().containsKey(root.resolve("ignored"))); assertEquals(1, repository.ignoreQueries.get());
        int count = repository.queries.get(); service.refresh(); await(() -> repository.queries.get() > count); Thread.sleep(30);
        assertEquals(1, repository.ignoreQueries.get()); assertEquals(1, started.get());
        service.close(); assertEquals(1, stopped.get());
    }
    @Test void latestProjectWinsAndDisposalSuppressesQueuedDeliveries() throws Exception {
        Path first = project("first"), second = project("second"); List<ProjectGitStatus> delivered = new ArrayList<>();
        AutoCloseable subscription = service.subscribe(delivered::add);
        repository.block = new CountDownLatch(1); service.project(first); assertTrue(repository.entered.await(2, TimeUnit.SECONDS));
        service.project(second); repository.block.countDown();
        await(() -> !delivered.isEmpty() && delivered.getLast().available() && second.equals(delivered.getLast().project()));
        assertTrue(delivered.stream().noneMatch(snapshot -> first.equals(snapshot.project())));
        int before = delivered.size(); repository.revision.incrementAndGet(); service.refresh(); Thread.sleep(40); subscription.close(); subscription.close();
        await(() -> dispatch.isEmpty()); assertEquals(before, delivered.size());
    }
    @Test void failuresAreStaleRemovalClearsAndCallbackFailuresAreIsolated() throws Exception {
        Path root = project("repo"); List<ProjectGitStatus> delivered = new ArrayList<>();
        service.subscribe(snapshot -> { throw new IllegalStateException("bad consumer"); }); service.subscribe(delivered::add); service.project(root);
        await(() -> !delivered.isEmpty() && delivered.getLast().available()); assertTrue(errors.get() > 0);
        repository.fail = true; service.refresh(); await(() -> delivered.getLast().phase() == ProjectGitStatus.Phase.STALE); assertTrue(delivered.getLast().available());
        Files.delete(root.resolve(".git")); service.refresh(); await(() -> delivered.getLast().phase() == ProjectGitStatus.Phase.NO_REPOSITORY); assertTrue(delivered.getLast().paths().isEmpty()); assertFalse(delivered.getLast().available());
        repository.fail = false; Files.createDirectory(root.resolve(".git")); service.refresh(); await(() -> delivered.getLast().available()); assertEquals(2, started.get());
    }
    @Test void invalidationBurstsStaySerializedAndRecheckAfterRunningQuery() throws Exception {
        Path root = project("repo"); List<ProjectGitStatus> delivered = new ArrayList<>(); service.subscribe(delivered::add);
        repository.block = new CountDownLatch(1); service.project(root); assertTrue(repository.entered.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 100; i++) service.invalidate(); repository.revision.incrementAndGet(); repository.block.countDown();
        await(() -> repository.queries.get() >= 2 && !delivered.isEmpty() && delivered.getLast().available()); assertEquals(1, repository.maximumConcurrent.get()); assertTrue(repository.queries.get() <= 3);
    }
    @Test void pathAndSubscriberLimitsAreExplicitAndIndependentWindowsDoNotShareState() throws Exception {
        Path root = project("repo"), other = project("other");
        List<ProjectGitStatus> delivered = new ArrayList<>(), independent = new ArrayList<>();
        try (ProjectGitStatusService bounded = new ProjectGitStatusService(repository,
                (location, changed) -> () -> { }, workers, scheduler, dispatch::add,
                new ProjectGitStatusService.Configuration(Duration.ZERO, Duration.ofDays(1), 1, 1), error -> { })) {
            bounded.subscribe(delivered::add); service.subscribe(independent::add);
            assertThrows(IllegalStateException.class, () -> bounded.subscribe(snapshot -> { }));
            bounded.project(root); service.project(other);
            bounded.observedPaths(Set.of(root.resolve("ignored"), root.resolve("plain"), temporary.resolve("outside")));
            await(() -> !delivered.isEmpty() && delivered.getLast().available() && delivered.getLast().message().contains("limited")
                    && !independent.isEmpty() && independent.getLast().available());
            assertTrue(delivered.getLast().paths().containsKey(root.resolve("ignored")));
            assertEquals(other, independent.getLast().project()); assertFalse(independent.getLast().paths().containsKey(root.resolve("ignored")));
        }
    }
    @Test void statusUsesSemanticPrecedenceWithoutLeakingMutableData() {
        assertEquals(GitPathStatus.Kind.UNTRACKED, GitPathStatus.from(new GitChange("a", "a", "Untracked", '?', '?', false)).kind());
        assertEquals(GitPathStatus.Kind.ADDED, GitPathStatus.from(new GitChange("a", "a", "Added", 'A', 'M', false)).kind());
        assertEquals(GitPathStatus.Kind.CONFLICT, GitPathStatus.from(new GitChange("a", "a", "Unmerged", 'A', 'A', true)).kind());
        for (char[] indicators : List.of(new char[]{'M', '.'}, new char[]{'M', 'M'}, new char[]{'D', '?'}, new char[]{'R', '.'}, new char[]{'.', 'T'}))
            assertEquals(GitPathStatus.Kind.MODIFIED, GitPathStatus.from(new GitChange("a", "a", "Mixed", indicators[0], indicators[1], false)).kind());
    }
    private static final class FakeRepository implements GitRepository {
        final AtomicInteger queries = new AtomicInteger(), ignoreQueries = new AtomicInteger(), revision = new AtomicInteger();
        final AtomicInteger concurrent = new AtomicInteger(), maximumConcurrent = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch block; volatile boolean fail;
        public GitFileRevision fileRevision(RepositoryLocation location, String commit, String path) { throw new UnsupportedOperationException(); }
        public List<GitBlameLine> blame(RepositoryLocation location, String commit, String path) { throw new UnsupportedOperationException(); }
        public RepositoryLocation locate(Path root) { return new RepositoryLocation(root, root.resolve(".git"), root.resolve(".git")); }
        public LocalGitStatus status(RepositoryLocation location) throws IOException {
            queries.incrementAndGet(); int active = concurrent.incrementAndGet(); maximumConcurrent.accumulateAndGet(active, Math::max); entered.countDown();
            try {
                CountDownLatch wait = block; if (wait != null) try { wait.await(); } catch (InterruptedException ignored) { /* Simulate an obsolete completion. */ }
                if (fail) throw new IOException("Temporary index failure");
                return new LocalGitStatus("a".repeat(40), "main", List.of(new GitChange("modified", "modified", "Modified", '.', 'M', false)), "revision " + revision.get(), "refs");
            } finally { concurrent.decrementAndGet(); }
        }
        public Set<String> ignored(RepositoryLocation location, List<String> paths) { ignoreQueries.incrementAndGet(); return paths.contains("ignored") ? Set.of("ignored") : Set.of(); }
        public String ignoreFingerprint(RepositoryLocation location, Set<String> paths) { return "rules"; }
        public RepositoryState state(RepositoryLocation location) { throw new AssertionError("Local status must not request the full workspace state"); }
        public String statusFingerprint(RepositoryLocation root, List<GitChange> paths) { return "revision " + revision.get(); }
        public List<GitRef> references(RepositoryLocation location) { throw new AssertionError("Local status must not load refs"); }
        public FileHistoryPage fileHistory(RepositoryLocation root, FileHistoryQuery query, int offset, int count) { return new FileHistoryPage(List.of(), false); }
        public HistoryPage history(RepositoryLocation location, HistoryQuery query, int offset, int count) { throw new AssertionError(); }
        public GitCommit commit(RepositoryLocation location, String id) { throw new AssertionError(); }
        public String resolve(RepositoryLocation location, String revision) { throw new AssertionError(); }
        public List<GitChange> changes(RepositoryLocation location, GitSource left, GitSource right) { throw new AssertionError(); }
        public GitContent content(RepositoryLocation location, GitSource source, String path) { throw new AssertionError(); }
        public String fingerprint(RepositoryLocation location, List<GitChange> paths) { throw new AssertionError(); }
    }
}
