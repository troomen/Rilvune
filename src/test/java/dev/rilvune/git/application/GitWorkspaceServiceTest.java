package dev.rilvune.git.application;

import dev.rilvune.git.domain.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GitWorkspaceServiceTest {
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Queue<Runnable> notifications = new ConcurrentLinkedQueue<>();
    private final FakeRepository repository = new FakeRepository();
    private final GitWorkspaceService service = new GitWorkspaceService(repository, workers, notifications::add);
    @AfterEach void close() { service.close(); workers.shutdownNow(); }
    private void deliver() throws Exception { long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); while (notifications.isEmpty() && System.nanoTime() < until) Thread.sleep(5); Runnable next; while ((next = notifications.poll()) != null) next.run(); }
    private void project(String name) throws Exception { service.bind(repository.locate(Path.of("/" + name))); }
    @Test void lateProjectResultsCannotRepopulateTheNextProject() throws Exception {
        project("first"); CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1); repository.waiting = waiting; repository.release = release;
        AtomicBoolean old = new AtomicBoolean(); service.references(ignored -> old.set(true), ignored -> old.set(true)); assertTrue(waiting.await(2, TimeUnit.SECONDS));
        project("second"); release.countDown(); deliver(); assertFalse(old.get());
    }
    @Test void latestSelectionWinsEvenIfTheOldQueryIgnoresInterruption() throws Exception {
        project("repo"); CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1); repository.waiting = waiting; repository.release = release;
        List<String> results = new ArrayList<>(); service.commit("old", commit -> results.add(commit.id()), error -> fail(error)); assertTrue(waiting.await(2, TimeUnit.SECONDS));
        repository.waiting = null; repository.release = null; service.commit("new", commit -> results.add(commit.id()), error -> fail(error)); deliver(); release.countDown(); deliver(); assertEquals(List.of("new"), results);
    }
    @Test void volatileComparisonRejectsRacingDiskSnapshots() throws Exception {
        project("repo"); repository.changeFingerprint = true; AtomicReference<String> failure = new AtomicReference<>();
        service.comparison(GitSource.index(), GitSource.working(), new GitChange("a", "a", "Modified", 'M', 'M', false), ignored -> fail("stale pair delivered"), failure::set); deliver(); assertTrue(failure.get().contains("Source changed"));
    }
    @Test void immutableComparisonDoesNotReadVolatileFingerprint() throws Exception {
        project("repo"); repository.changeFingerprint = true; String id = "a".repeat(40); AtomicBoolean complete = new AtomicBoolean();
        service.comparison(GitSource.tree(id, "a"), GitSource.tree(id, "b"), new GitChange("a", "a", "Modified", '.', '.', false), ignored -> complete.set(true), error -> fail(error)); deliver(); assertTrue(complete.get()); assertEquals(0, repository.fingerprints.get());
    }
    @Test void pauseSuppressesPendingCallbacks() throws Exception {
        project("repo"); AtomicBoolean delivered = new AtomicBoolean();
        service.references(ignored -> delivered.set(true), error -> fail(error));
        service.pause(); deliver(); assertFalse(delivered.get());
    }
    @Test void unstagedRenameReadsTheNewIndexPath() throws Exception {
        project("repo"); service.comparison(GitSource.index(), GitSource.working(), new GitChange("new", "old", "Renamed", 'R', 'M', false), ignored -> { }, error -> fail(error)); deliver(); assertEquals(List.of("new", "new"), repository.contentPaths);
    }
    @Test void historyContextResolvesLocalRenameAndMapsTheCapturedSelection() throws Exception {
        project("repo");
        repository.localChanges = List.of(new GitChange("new.txt", "old.txt", "Renamed", 'R', '.', false));
        AtomicReference<GitWorkspaceService.FileHistoryContext> result = new AtomicReference<>();
        service.fileHistoryContext(Path.of("/repo"), Path.of("/repo/new.txt"), "local\na\nb\n",
                new FileHistoryQuery.Lines(1, 3), new dev.rilvune.diff.infrastructure.MyersDiffEngine(dev.rilvune.diff.infrastructure.DiffLimits.interactive()), result::set, error -> fail(error));
        deliver();
        assertEquals("old.txt", result.get().query().path());
        assertEquals(new FileHistoryQuery.Lines(0, 2), result.get().query().selection());
    }
    @Test void obsoleteHistoryContextCannotRebindTheNextProject() throws Exception {
        project("first"); CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        repository.waiting = waiting; repository.release = release;
        AtomicBoolean delivered = new AtomicBoolean();
        service.fileHistoryContext(Path.of("/first"), Path.of("/first/file"), null, null,
                new dev.rilvune.diff.infrastructure.MyersDiffEngine(dev.rilvune.diff.infrastructure.DiffLimits.interactive()), ignored -> delivered.set(true), ignored -> delivered.set(true));
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        project("second"); repository.waiting = null; repository.release = null; release.countDown(); deliver();
        assertFalse(delivered.get());
        service.fileHistory(new FileHistoryQuery("a".repeat(40), "file", null), 0, 10, ignored -> { }, error -> fail(error)); deliver();
        assertEquals(Path.of("/second"), repository.historyRoot);
    }
    @Test void pausingHistorySuppressesItsLatePage() throws Exception {
        project("repo"); CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        repository.waiting = waiting; repository.release = release;
        AtomicBoolean delivered = new AtomicBoolean();
        service.fileHistory(new FileHistoryQuery("a".repeat(40), "file", null), 0, 10, ignored -> delivered.set(true), ignored -> delivered.set(true));
        assertTrue(waiting.await(2, TimeUnit.SECONDS)); service.pause(); release.countDown(); deliver(); assertFalse(delivered.get());
    }
    private static final class FakeRepository implements GitRepository {
        volatile CountDownLatch waiting, release; boolean changeFingerprint; AtomicInteger fingerprints = new AtomicInteger(); List<String> contentPaths = new ArrayList<>();
        List<GitChange> localChanges = List.of(); volatile Path historyRoot;
        private void waitIfRequested() { CountDownLatch captured = release; if (captured != null) { waiting.countDown(); boolean interrupted = false; while (true) try { captured.await(); break; } catch (InterruptedException ignored) { interrupted = true; } if (interrupted) Thread.currentThread().interrupt(); } }
        public GitFileRevision fileRevision(RepositoryLocation location, String commit, String path) { return new GitFileRevision("b".repeat(40), path, new GitContent("a\nb\n", "UTF-8", "", true)); }
        public List<GitBlameLine> blame(RepositoryLocation location, String commit, String path) { throw new UnsupportedOperationException(); }
        public RepositoryLocation locate(Path root) { return new RepositoryLocation(root, root.resolve(".git"), root.resolve(".git")); }
        public LocalGitStatus status(RepositoryLocation root) { waitIfRequested(); return new LocalGitStatus("a".repeat(40), "main", localChanges, "stable", "refs"); }
        public String statusFingerprint(RepositoryLocation root, List<GitChange> paths) { return "stable"; }
        public List<GitRef> references(RepositoryLocation root) { waitIfRequested(); return List.of(); }
        public Set<String> ignored(RepositoryLocation root, List<String> paths) { return Set.of(); }
        public String ignoreFingerprint(RepositoryLocation root, Set<String> paths) { return "stable"; }
        public RepositoryState state(RepositoryLocation root) { waitIfRequested(); return new RepositoryState("", "main", List.of(), List.of(), "stable"); }
        public FileHistoryPage fileHistory(RepositoryLocation root, FileHistoryQuery query, int offset, int count) { waitIfRequested(); historyRoot = root.root(); return new FileHistoryPage(List.of(), false); }
        public HistoryPage history(RepositoryLocation root, HistoryQuery query, int offset, int count) { return new HistoryPage(List.of(), false); }
        public GitCommit commit(RepositoryLocation root, String id) { waitIfRequested(); return new GitCommit(id, List.of(), "", "", ""); }
        public String resolve(RepositoryLocation root, String id) { return id; }
        public List<GitChange> changes(RepositoryLocation root, GitSource left, GitSource right) { return List.of(); }
        public GitContent content(RepositoryLocation root, GitSource source, String path) { contentPaths.add(path); return GitContent.empty(); }
        public String fingerprint(RepositoryLocation root, List<GitChange> changes) { int index = fingerprints.incrementAndGet(); return changeFingerprint ? "revision:" + index : "stable"; }
    }
}
