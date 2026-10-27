package dev.rilvune.search.application;

import dev.rilvune.document.application.DocumentHandle;
import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.domain.DraftRepository;
import dev.rilvune.document.domain.RecoveryDraft;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ProjectReplacementTest {
    @TempDir Path directory;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> afterIoDispatch = new java.util.concurrent.atomic.AtomicReference<>();
    private final ExecutorService io = new AbstractExecutorService() {
        @Override public void execute(Runnable command) {
            Runnable intercept = afterIoDispatch.getAndSet(null);
            workers.execute(command);
            if (intercept != null) intercept.run();
        }
        @Override public void shutdown() { workers.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return workers.shutdownNow(); }
        @Override public boolean isShutdown() { return workers.isShutdown(); }
        @Override public boolean isTerminated() { return workers.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return workers.awaitTermination(timeout, unit); }
    };
    private final ExecutorService editing = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean failRelease = new AtomicBoolean();
    private final java.util.Set<Path> failWrites = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> failRecovery = ConcurrentHashMap.newKeySet();
    private FileDraftRepository storage;
    private DocumentService documents;
    private ProjectReplacement replacement;

    @BeforeEach void setup() {
        storage = new FileDraftRepository(directory.resolve("drafts"));
        DraftRepository drafts = new DraftRepository() {
            @Override public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            @Override public void save(RecoveryDraft draft) throws IOException {
                if (failRecovery.contains(draft.id())) throw new IOException("Recovery checkpoint failed");
                storage.save(draft);
            }
            @Override public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            @Override public void remove(UUID id) throws IOException {
                if (failRecovery.contains(id)) throw new IOException("Recovery checkpoint failed");
                storage.remove(id);
            }
            @Override public void release(UUID id) throws IOException {
                if (failRelease.get()) throw new IOException("Recovery release unavailable");
                storage.release(id);
            }
        };
        var nio = new NioDocumentStore(10000);
        var store = new dev.rilvune.document.domain.DocumentStore() {
            @Override public Path canonicalPath(Path path) throws IOException { return nio.canonicalPath(path); }
            @Override public dev.rilvune.document.domain.DiskSnapshot read(Path path) throws IOException { return nio.read(path); }
            @Override public dev.rilvune.document.domain.DiskSnapshot write(Path path, String text,
                    dev.rilvune.document.domain.TextFormat format, dev.rilvune.document.domain.DiskSnapshot expected) throws IOException {
                if (failWrites.contains(path)) throw new IOException("Source write failed");
                return nio.write(path, text, format, expected);
            }
        };
        documents = new DocumentService(store, drafts, io, scheduler, Runnable::run, Duration.ofDays(1));
        replacement = new ProjectReplacement(documents, editing);
    }

    @AfterEach void close() throws Exception {
        failRelease.set(false); failWrites.clear(); failRecovery.clear();
        documents.close().get(5, TimeUnit.SECONDS);
        scheduler.shutdownNow(); editing.shutdownNow(); io.close();
    }

    @Test void consumerAcquiredAfterPreparationSurvivesPlanRelease() throws Exception {
        Path file = file("a.txt");
        var plan = prepare(file);
        DocumentHandle tab = documents.acquire(file).get(5, TimeUnit.SECONDS);
        replacement.release(plan).get(5, TimeUnit.SECONDS);
        assertFalse(tab.session().isClosed(), "Releasing replacement must not close a later tab");
        tab.session().replaceText("still editable");
        tab.release().get(5, TimeUnit.SECONDS);
        assertEquals("still editable", Files.readString(file));
    }

    @Test void closingLastViewDoesNotClosePreparedReplacement() throws Exception {
        Path file = file("a.txt");
        DocumentHandle tab = documents.acquire(file).get(5, TimeUnit.SECONDS);
        var plan = prepare(file);
        tab.release().get(5, TimeUnit.SECONDS);
        assertFalse(tab.session().isClosed(), "Replacement must retain its own claim");
        replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertEquals("updated", Files.readString(file));
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void preparationReportsPrimaryAndCleanupFailure() throws Exception {
        Path file = file("a.txt");
        failRelease.set(true);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> replacement.prepare(
                List.of(hit(file), hit(directory.resolve("missing.txt"))), "needle", "updated", true, false).get(5, TimeUnit.SECONDS));
        assertTrue(hasMessage(failure, "Recovery release unavailable"), "Failed preparation must retain cleanup diagnostics");
    }

    @Test void existingConsumerSurvivesSuccessfulApplyAndFormattingIsPreserved() throws Exception {
        Path file = Files.writeString(directory.resolve("format.txt"), "\uFEFFneedle\r\nneedle\r\n");
        DocumentHandle tab = documents.acquire(file).get(5, TimeUnit.SECONDS);
        var result = replacement.apply(prepare(file)).get(5, TimeUnit.SECONDS);
        assertTrue(result.successful(), result.details());
        assertEquals(2, result.appliedMatches());
        assertEquals(1, result.appliedFiles());
        assertFalse(tab.session().isClosed());
        assertEquals("\uFEFFupdated\r\nupdated\r\n", Files.readString(file));
        tab.release().get(5, TimeUnit.SECONDS);
    }

    @Test void finalReleaseFailureIsRetryableAndNeverReappliesEdits() throws Exception {
        Path file = file("a.txt");
        var plan = prepare(file);
        failRelease.set(true);
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertFalse(result.successful());
        assertEquals(1, result.appliedMatches());
        assertEquals(ReplacementResult.Persistence.SYNCHRONIZED, result.files().getFirst().persistence());
        assertFalse(result.files().getFirst().released());
        assertTrue(replacement.cleanupPending());
        assertThrows(ExecutionException.class, () -> replacement.apply(plan).get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> prepare(file));
        DocumentHandle tab = documents.acquire(file).get(5, TimeUnit.SECONDS);
        tab.session().replaceText("a later edit");
        replacement.retryCleanup().get(5, TimeUnit.SECONDS); // Non-final release now succeeds even while storage fails.
        assertFalse(replacement.cleanupPending());
        assertFalse(tab.session().isClosed());
        assertEquals("a later edit", tab.session().text());
        failRelease.set(false);
        tab.release().get(5, TimeUnit.SECONDS);
        assertEquals("a later edit", Files.readString(file));
    }

    @Test void failedPreparationRetainsCleanupEvenWithoutAPublishedPlan() throws Exception {
        Path file = file("a.txt");
        failRelease.set(true);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> replacement.prepare(
                List.of(hit(file), hit(directory.resolve("missing.txt"))), "needle", "updated", true, false).get(5, TimeUnit.SECONDS));
        var aggregate = assertInstanceOf(ReplacementFailure.class, failure.getCause());
        assertEquals(2, aggregate.problems().size());
        assertEquals(ReplacementProblem.Phase.PREPARATION, aggregate.problems().getFirst().phase());
        assertEquals(ReplacementProblem.Phase.CLEANUP, aggregate.problems().getLast().phase());
        assertTrue(replacement.cleanupPending());
        assertThrows(ExecutionException.class, () -> replacement.quiesce().get(5, TimeUnit.SECONDS));
        failRelease.set(false);
        replacement.quiesce().get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty());
        assertThrows(ExecutionException.class, () -> prepare(file));
        replacement.resume();
        replacement.release(prepare(file)).get(5, TimeUnit.SECONDS);
    }

    @Test void everyFailedReleaseIsReportedAndOnlyFailedHandlesAreRetried() throws Exception {
        Path first = file("a.txt"), second = file("b.txt"), third = file("c.txt");
        var plan = prepare(first, second, third);
        DocumentHandle tab = documents.acquire(first).get(5, TimeUnit.SECONDS);
        failRelease.set(true);
        var failure = assertThrows(ExecutionException.class, () -> replacement.release(plan).get(5, TimeUnit.SECONDS));
        var aggregate = assertInstanceOf(ReplacementFailure.class, failure.getCause());
        assertEquals(2, aggregate.problems().size());
        assertEquals(2, documents.sessions().stream().filter(session -> session.isClosing() || !session.isClosed()).filter(session -> !session.path().equals(first)).count());
        assertEquals(1, aggregate.getSuppressed().length);
        failRelease.set(false);
        replacement.release(plan).get(5, TimeUnit.SECONDS);
        replacement.release(plan).get(5, TimeUnit.SECONDS);
        assertFalse(tab.session().isClosed());
        assertEquals(1, documents.sessions().size());
        tab.release().get(5, TimeUnit.SECONDS);
    }

    @Test void consumedAndForeignPlansCannotBeAppliedOrReleasedByAnotherService() throws Exception {
        Path file = file("a.txt");
        var plan = prepare(file);
        var other = new ProjectReplacement(documents, editing);
        assertThrows(ExecutionException.class, () -> other.apply(plan).get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> other.release(plan).get(5, TimeUnit.SECONDS));
        replacement.release(plan).get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> replacement.apply(plan).get(5, TimeUnit.SECONDS));
        assertEquals("needle", Files.readString(file));
    }

    @Test void duplicatePathsAndCanonicalAliasesAreEditedOnce() throws Exception {
        Path file = file("a.txt");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), file.getFileName());
        var plan = prepare(file, file, alias);
        assertEquals(1, plan.files().size());
        assertEquals(1, plan.matches());
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertTrue(result.successful(), result.details());
        assertEquals(1, result.appliedMatches());
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void emptyAndZeroMatchPlansReleaseWithoutEditing() throws Exception {
        var empty = replacement.prepare(List.of(), "needle", "updated", true, false).get(5, TimeUnit.SECONDS);
        assertTrue(replacement.apply(empty).get(5, TimeUnit.SECONDS).successful());
        Path file = Files.writeString(directory.resolve("no-match.txt"), "different");
        var plan = prepare(file);
        assertEquals(0, plan.changedFiles());
        assertEquals(0, plan.matches());
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertEquals(ReplacementResult.BufferEffect.NO_MATCH, result.files().getFirst().effect());
        assertTrue(result.successful());
        assertEquals("different", Files.readString(file));
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void invalidSingleOffsetStillReleasesItsAcquisition() throws Exception {
        Path file = file("a.txt");
        assertThrows(ExecutionException.class, () -> replacement.prepare(
                List.of(new ProjectSearch.Hit(file, 1, "", 999)), "needle", "updated", true, true).get(5, TimeUnit.SECONDS));
        assertTrue(documents.sessions().isEmpty());
        assertEquals("needle", Files.readString(file));
    }

    @Test void pausedOrRemappedTargetRejectsTheWholePlanBeforeEditing() throws Exception {
        Path first = file("a.txt"), second = file("b.txt");
        DocumentHandle tab = documents.acquire(second).get(5, TimeUnit.SECONDS);
        var pausedPlan = prepare(first, second);
        tab.session().pause().get(5, TimeUnit.SECONDS);
        var paused = replacement.apply(pausedPlan).get(5, TimeUnit.SECONDS);
        assertEquals(0, paused.appliedMatches());
        assertEquals(ReplacementProblem.Phase.PREFLIGHT, paused.problems().getFirst().phase());
        assertEquals("needle", Files.readString(first));
        tab.session().resume(); tab.session().flush().get(5, TimeUnit.SECONDS);
        var remappedPlan = prepare(first, second);
        Path moved = directory.resolve("moved.txt");
        tab.session().pause().get(5, TimeUnit.SECONDS);
        Files.move(second, moved);
        tab.session().remapPath(moved).get(5, TimeUnit.SECONDS);
        tab.session().resume(); tab.session().flush().get(5, TimeUnit.SECONDS);
        var remapped = replacement.apply(remappedPlan).get(5, TimeUnit.SECONDS);
        assertEquals(0, remapped.appliedMatches());
        assertEquals(moved, remapped.problems().getFirst().currentPath());
        assertEquals("needle", Files.readString(first));
        tab.release().get(5, TimeUnit.SECONDS);
    }

    @Test void cancelledObserverDoesNotCancelInternalPreparationOrDrain() throws Exception {
        Path file = file("a.txt");
        var queued = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        replacement = new ProjectReplacement(documents, queued::add);
        var observer = replacement.prepare(List.of(hit(file)), "needle", "updated", true, false);
        Runnable calculate = queued.poll(5, TimeUnit.SECONDS);
        assertNotNull(calculate); // Acquisition is already registered before dispatch.
        assertTrue(observer.cancel(false));
        var drain = replacement.quiesce();
        assertFalse(drain.isDone());
        calculate.run();
        drain.get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty());
        replacement.resume();
    }

    @Test void rejectedEditingDispatchReleasesAcquiredHandle() throws Exception {
        Path file = file("a.txt");
        replacement = new ProjectReplacement(documents, command -> { throw new RejectedExecutionException("Executor stopped"); });
        var failure = assertThrows(ExecutionException.class, () -> prepare(file));
        assertTrue(hasMessage(failure, "Executor stopped"));
        assertTrue(documents.sessions().isEmpty());
        assertFalse(replacement.cleanupPending());
    }

    @Test void applyWinsAgainstReleaseAndQuiescenceUntilItsCleanupSettles() throws Exception {
        Path file = file("a.txt");
        var queued = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        replacement = new ProjectReplacement(documents, queued::add);
        var preparing = replacement.prepare(List.of(hit(file)), "needle", "updated", true, false);
        while (!preparing.isDone()) {
            Runnable calculation = queued.poll(5, TimeUnit.SECONDS); assertNotNull(calculation); calculation.run();
        }
        var plan = preparing.get(5, TimeUnit.SECONDS);
        var application = replacement.apply(plan);
        var release = replacement.release(plan);
        var drain = replacement.quiesce();
        assertFalse(release.isDone()); assertFalse(drain.isDone());
        Runnable edit = queued.poll(5, TimeUnit.SECONDS); assertNotNull(edit); edit.run();
        assertTrue(application.get(5, TimeUnit.SECONDS).successful());
        release.get(5, TimeUnit.SECONDS); drain.get(5, TimeUnit.SECONDS);
        assertEquals("updated", Files.readString(file));
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void quiescenceConsumesAReadyPlanAndResumeAllowsFreshWork() throws Exception {
        Path file = file("a.txt");
        var plan = prepare(file);
        replacement.quiesce().get(5, TimeUnit.SECONDS);
        assertFalse(plan.ready());
        assertThrows(ExecutionException.class, () -> replacement.apply(plan).get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> prepare(file));
        replacement.resume();
        replacement.release(prepare(file)).get(5, TimeUnit.SECONDS);
        assertEquals("needle", Files.readString(file));
    }

    @Test void sourceFailureKeepsRecoveryAndDoesNotHideAnotherFilesSuccessfulWrite() throws Exception {
        Path first = file("a.txt"), second = file("b.txt");
        var plan = prepare(first, second);
        failWrites.add(first);
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertFalse(result.successful());
        assertEquals(2, result.appliedMatches());
        assertEquals(ReplacementResult.Persistence.NEEDS_ATTENTION, result.files().getFirst().persistence());
        assertEquals(ReplacementResult.Persistence.SYNCHRONIZED, result.files().getLast().persistence());
        assertEquals("needle", Files.readString(first));
        assertEquals("updated", Files.readString(second));
        assertEquals("updated", storage.load().getFirst().text());
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void exceptionalFlushAndCleanupFailuresAreBothRetainedWhileOtherFilesFinish() throws Exception {
        Path first = file("a.txt"), second = file("b.txt");
        var plan = prepare(first, second);
        var session = documents.sessions().stream().filter(document -> document.path().equals(first)).findFirst().orElseThrow();
        failRecovery.add(session.id());
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertEquals(ReplacementResult.Persistence.FAILED, result.files().getFirst().persistence());
        assertEquals(ReplacementResult.Persistence.SYNCHRONIZED, result.files().getLast().persistence());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.phase() == ReplacementProblem.Phase.PERSISTENCE));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.phase() == ReplacementProblem.Phase.CLEANUP));
        assertFalse(result.files().getFirst().released());
        assertTrue(result.files().getLast().released());
        failRecovery.clear();
        replacement.retryCleanup().get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void cancelledApplyAndCleanupObserversDoNotInterruptTheirWork() throws Exception {
        Path file = file("a.txt");
        var queued = new LinkedBlockingQueue<Runnable>();
        replacement = new ProjectReplacement(documents, queued::add);
        var preparing = replacement.prepare(List.of(hit(file)), "needle", "updated", true, false);
        while (!preparing.isDone()) {
            Runnable calculation = queued.poll(5, TimeUnit.SECONDS); assertNotNull(calculation); calculation.run();
        }
        var plan = preparing.get(5, TimeUnit.SECONDS);
        var application = replacement.apply(plan);
        var releasing = replacement.release(plan);
        application.cancel(false); releasing.cancel(false);
        var drain = replacement.quiesce();
        Runnable edit = queued.poll(5, TimeUnit.SECONDS); assertNotNull(edit); edit.run();
        drain.get(5, TimeUnit.SECONDS);
        assertEquals("updated", Files.readString(file));
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void stateChangeAfterPreflightProducesAccuratePartialProgress() throws Exception {
        Path first = file("a.txt"), second = file("b.txt"), third = file("c.txt");
        DocumentHandle tab = documents.acquire(second).get(5, TimeUnit.SECONDS);
        var plan = prepare(first, second, third);
        afterIoDispatch.set(() -> {
            try { tab.session().pause().get(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertFalse(result.successful());
        assertEquals(1, result.appliedMatches());
        assertEquals(ReplacementResult.BufferEffect.APPLIED, result.files().getFirst().effect());
        assertEquals(ReplacementResult.BufferEffect.REJECTED, result.files().get(1).effect());
        assertEquals(ReplacementResult.BufferEffect.NOT_ATTEMPTED, result.files().getLast().effect());
        assertEquals(ReplacementProblem.Phase.APPLICATION, result.problems().getFirst().phase());
        assertEquals("updated", Files.readString(first));
        assertEquals("needle", Files.readString(second));
        assertEquals("needle", Files.readString(third));
        assertFalse(tab.session().isClosed());
        tab.session().resume();
        tab.release().get(5, TimeUnit.SECONDS);
    }

    @Test void concurrentCleanupRequestsShareOneFinalReleaseAttempt() throws Exception {
        Path file = file("a.txt");
        var plan = prepare(file);
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        afterIoDispatch.set(() -> {
            entered.countDown();
            try { assertTrue(proceed.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new CompletionException(failure); }
        });
        var first = CompletableFuture.supplyAsync(() -> replacement.release(plan), workers).thenCompose(future -> future);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var second = replacement.release(plan);
        assertFalse(second.isDone());
        proceed.countDown();
        first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty());
        replacement.release(plan).get(5, TimeUnit.SECONDS);
    }

    @Test void dispatchFailureAfterMutationStillReportsAndFlushesTheAppliedBuffer() throws Exception {
        Path first = file("a.txt"), second = file("b.txt");
        var plan = prepare(first, second);
        afterIoDispatch.set(() -> { throw new RejectedExecutionException("Failure after dispatch"); });
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertFalse(result.successful());
        assertEquals(1, result.appliedMatches());
        assertEquals(ReplacementResult.BufferEffect.APPLIED, result.files().getFirst().effect());
        assertEquals(ReplacementResult.BufferEffect.NOT_ATTEMPTED, result.files().getLast().effect());
        assertEquals("updated", Files.readString(first));
        assertEquals("needle", Files.readString(second));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.phase() == ReplacementProblem.Phase.APPLICATION));
    }

    @Test void rejectedDispatchAndFailedCleanupRemainRetryableWithoutAPublishedTarget() throws Exception {
        Path file = file("a.txt");
        failRelease.set(true);
        replacement = new ProjectReplacement(documents, command -> { throw new RejectedExecutionException("Executor stopped"); });
        var failure = assertThrows(ExecutionException.class, () -> prepare(file));
        assertTrue(hasMessage(failure, "Executor stopped"));
        assertTrue(hasMessage(failure, "Recovery release unavailable"));
        assertTrue(replacement.cleanupPending());
        failRelease.set(false);
        replacement.retryCleanup().get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty());
    }

    @Test void workerCalculationKeepsItsHandleAndRejectsAnEditMadeDuringPreparation() throws Exception {
        Path file = file("worker.txt");
        var calculations = new LinkedBlockingQueue<Runnable>();
        replacement = new ProjectReplacement(documents, Runnable::run, calculations::add, () -> { });
        var preparing = replacement.prepare(List.of(hit(file)), "needle", "updated", true, false);
        Runnable calculate = calculations.poll(5, TimeUnit.SECONDS); assertNotNull(calculate);
        var document = documents.sessions().getFirst();
        document.replaceText("newer text");
        calculate.run();
        var plan = preparing.get(5, TimeUnit.SECONDS);
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertFalse(result.successful()); assertEquals(0, result.appliedMatches());
        assertEquals("newer text", Files.readString(file)); assertTrue(documents.sessions().isEmpty());
    }

    @Test void quiescenceWaitsForWorkerCancellationAndReleasesThePreparationHandle() throws Exception {
        Path file = file("cancel-worker.txt");
        var calculations = new LinkedBlockingQueue<Runnable>();
        replacement = new ProjectReplacement(documents, Runnable::run, calculations::add, () -> { });
        var preparing = replacement.prepare(List.of(hit(file)), "needle", "updated", true, false);
        Runnable calculate = calculations.poll(5, TimeUnit.SECONDS); assertNotNull(calculate);
        var drain = replacement.quiesce(); assertFalse(drain.isDone());
        calculate.run(); drain.get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> preparing.get(5, TimeUnit.SECONDS));
        assertTrue(documents.sessions().isEmpty()); assertEquals("needle", Files.readString(file));
    }

    @Test void oversizedReplacementIsRejectedBeforeAnyBufferChanges() throws Exception {
        Path file = Files.writeString(directory.resolve("large-result.txt"), "a".repeat(33));
        assertThrows(ExecutionException.class, () -> replacement.prepare(List.of(hit(file)), "a", "b".repeat(1024 * 1024),
                true, false).get(5, TimeUnit.SECONDS));
        assertEquals("a".repeat(33), Files.readString(file)); assertTrue(documents.sessions().isEmpty());
    }

    private ProjectReplacement.Plan prepare(Path... paths) throws Exception {
        return replacement.prepare(java.util.Arrays.stream(paths).map(this::hit).toList(), "needle", "updated", true, false).get(5, TimeUnit.SECONDS);
    }
    private Path file(String name) throws IOException { return Files.writeString(directory.resolve(name), "needle"); }
    private ProjectSearch.Hit hit(Path path) { return new ProjectSearch.Hit(path, 1, "needle", 0); }
    private boolean hasMessage(Throwable failure, String value) {
        if (failure.getMessage() != null && failure.getMessage().contains(value)) return true;
        if (failure.getCause() != null && hasMessage(failure.getCause(), value)) return true;
        return java.util.Arrays.stream(failure.getSuppressed()).anyMatch(error -> hasMessage(error, value));
    }
}
