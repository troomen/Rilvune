package dev.editor.app.application;

import dev.editor.session.domain.*;
import dev.editor.workspace.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ProjectLifecycleTest {
    @TempDir Path directory;
    private final List<String> events = new ArrayList<>();
    private final List<ProjectContext> activations = new ArrayList<>();
    private CompletableFuture<Void> stop = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> restoration = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> documentClose = CompletableFuture.completedFuture(null);
    private CompletableFuture<Boolean> decision = CompletableFuture.completedFuture(true);
    private Workspace published;
    private boolean invalidTarget, failSave, failResume;
    private ProjectLifecycle lifecycle;
    private ProjectLifecycle.Host host;
    private SessionStore store;

    @BeforeEach void setup() throws Exception {
        Workspace initial = new Workspace(directory);
        store = new SessionStore() {
            public SessionState load(Path root) { events.add("load"); return SessionState.empty(root); }
            public void remember(Path root) { events.add("remember"); }
            public void save(SessionState state) throws IOException {
                events.add("save"); if (failSave) throw new IOException("Save unavailable");
            }
        };
        host = new ProjectLifecycle.Host() {
            public void transitioning(boolean active) { events.add(active ? "begin" : "end"); }
            public SessionState capture() { events.add("capture"); return SessionState.empty(directory); }
            public CompletableFuture<Boolean> closeViews() { events.add("close views"); return decision; }
            public void prepareRestore() { events.add("prepare restore"); }
            public CompletableFuture<Void> restore(Workspace workspace, SessionState state, boolean bounds) {
                events.add("restore"); return restoration;
            }
            public void publish(Workspace next) { published = next; events.add("publish"); }
            public void warning(Throwable failure) { events.add("warning"); }
            public CompletableFuture<Void> finish() { events.add("finish"); return CompletableFuture.completedFuture(null); }
        };
        var participant = new ProjectLifecycle.Participant() {
            public String name() { return "fake agent"; }
            public CompletableFuture<Void> quiesce() { events.add("stop"); return stop; }
            public void resume(ProjectContext context) {
                events.add("resume"); if (failResume) throw new IllegalStateException("Resume unavailable");
                activations.add(context);
            }
        };
        lifecycle = new ProjectLifecycle(initial, store, path -> {
            events.add("validate"); if (invalidTarget) throw new IOException("Invalid target"); return new Workspace(path);
        }, host, List.of(participant), () -> { events.add("recover"); return CompletableFuture.completedFuture(null); },
                () -> { events.add("close documents"); return documentClose; }, Runnable::run, Runnable::run);
        assertEquals(ProjectLifecycle.Outcome.COMPLETED, await(lifecycle.initialize()).outcome());
        events.clear();
    }
    private <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }

    @Test void switchingHasOneOrderAndPublishesOnlyAfterRestoration() throws Exception {
        ProjectContext before = lifecycle.context();
        restoration = new CompletableFuture<>();
        var operation = lifecycle.open(directory.resolve("next"));
        assertNull(lifecycle.context()); assertFalse(operation.isDone());
        assertEquals(directory, published.root());
        assertEquals(List.of("begin", "stop", "validate", "capture", "save", "close views", "prepare restore", "remember", "load", "restore"), events);
        restoration.complete(null);
        var result = await(operation);
        assertEquals(ProjectLifecycle.Outcome.COMPLETED, result.outcome());
        assertEquals(directory.resolve("next"), published.root());
        assertNotEquals(before, result.context());
        assertEquals(List.of("publish", "resume", "end"), events.subList(events.size() - 3, events.size()));
    }

    @Test void cancellationKeepsTheProjectWithAFreshActivation() throws Exception {
        ProjectContext old = lifecycle.context(); decision = CompletableFuture.completedFuture(false);
        var result = await(lifecycle.open(directory.resolve("next")));
        assertEquals(ProjectLifecycle.Outcome.CANCELLED, result.outcome());
        assertEquals(directory, result.context().root()); assertNotEquals(old, result.context());
        assertFalse(events.contains("restore"));
    }

    @Test void failedStopKeepsOwnershipAndRetryDoesNotRepeatDocumentChanges() throws Exception {
        int resumed = activations.size();
        stop = CompletableFuture.failedFuture(new IOException("Process still owns files"));
        var failed = await(lifecycle.open(directory.resolve("next")));
        assertEquals(ProjectLifecycle.Stage.QUIESCING, failed.stage()); assertEquals(ProjectLifecycle.Outcome.FAILED, failed.outcome());
        assertNull(lifecycle.context()); assertFalse(events.contains("close views")); assertEquals(resumed, activations.size());
        stop = CompletableFuture.completedFuture(null); events.clear();
        assertEquals(ProjectLifecycle.Outcome.COMPLETED, await(lifecycle.open(directory.resolve("next"))).outcome());
        assertEquals(1, Collections.frequency(events, "restore"));
    }

    @Test void observerCancellationAndBusyRequestsCannotAbandonTheTransition() throws Exception {
        stop = new CompletableFuture<>();
        var observer = lifecycle.open(directory.resolve("next")); observer.cancel(false);
        assertEquals(ProjectLifecycle.Outcome.BUSY, await(lifecycle.closeProject()).outcome());
        assertTrue(lifecycle.busy()); assertNull(lifecycle.context());
        stop.complete(null);
        assertFalse(lifecycle.busy()); assertEquals(directory.resolve("next"), lifecycle.context().root());
        assertEquals(1, Collections.frequency(events, "close views"));
    }

    @Test void validationOrSaveFailureCanSafelyReactivateOldViews() throws Exception {
        var old = lifecycle.context(); invalidTarget = true;
        var invalid = await(lifecycle.open(directory.resolve("next")));
        assertEquals(ProjectLifecycle.Outcome.FAILED, invalid.outcome()); assertEquals(ProjectLifecycle.Stage.VALIDATING, invalid.stage());
        assertNotEquals(old, lifecycle.context()); assertEquals(directory, lifecycle.context().root());
        invalidTarget = false; failSave = true;
        var failed = await(lifecycle.open(directory.resolve("next")));
        assertEquals(ProjectLifecycle.Stage.SAVING, failed.stage()); assertNotNull(lifecycle.context());
        assertFalse(events.contains("close views"));
    }

    @Test void criticalRestoreFailureShowsActualPartialWorkspaceWithoutEnablingWriters() throws Exception {
        restoration = CompletableFuture.failedFuture(new IOException("Tree unavailable"));
        var failed = await(lifecycle.open(directory.resolve("next")));
        assertEquals(ProjectLifecycle.Outcome.FAILED, failed.outcome()); assertEquals(ProjectLifecycle.Stage.RESTORING, failed.stage());
        assertEquals(directory.resolve("next"), published.root()); assertNull(lifecycle.context());
        assertFalse(lifecycle.busy());
    }

    @Test void failedViewCleanupDoesNotResumeProducers() throws Exception {
        int resumed = activations.size(); decision = CompletableFuture.failedFuture(new IOException("Release unavailable"));
        var failed = await(lifecycle.closeProject());
        assertEquals(ProjectLifecycle.Stage.CLOSING_VIEWS, failed.stage()); assertNull(lifecycle.context());
        assertEquals(resumed, activations.size());
        decision = CompletableFuture.completedFuture(true);
        assertEquals(ProjectLifecycle.Outcome.COMPLETED, await(lifecycle.closeProject()).outcome()); assertNull(published);
    }

    @Test void exitWaitsForActualDocumentShutdownBeforeFinishingHost() throws Exception {
        documentClose = new CompletableFuture<>(); var exiting = lifecycle.exit();
        assertFalse(exiting.isDone()); assertFalse(events.contains("finish"));
        documentClose.complete(null); assertEquals(ProjectLifecycle.Outcome.COMPLETED, await(exiting).outcome());
        assertTrue(events.indexOf("close documents") < events.indexOf("finish"));
        assertEquals(ProjectLifecycle.Outcome.BUSY, await(lifecycle.open(directory)).outcome());
    }

    @Test void snapshotShutdownUsesTheSameStopOrderWithoutAskingToCloseViews() throws Exception {
        assertEquals(ProjectLifecycle.Outcome.COMPLETED, await(lifecycle.finishSnapshot()).outcome());
        assertFalse(events.contains("close views")); assertTrue(events.contains("close documents")); assertTrue(events.contains("stop"));
    }

    @Test void periodicSaveFinishesBeforeTheTransitionSaveAndRestore() throws Exception {
        var workers = new ArrayDeque<Runnable>();
        var owner = new ProjectLifecycle(new Workspace(directory), store, path -> {
            events.add("validate"); return new Workspace(path);
        }, host, List.of(), () -> CompletableFuture.completedFuture(null), () -> CompletableFuture.completedFuture(null), Runnable::run, workers::add);
        var periodic = owner.persist(); var transition = owner.open(directory.resolve("next"));
        assertFalse(periodic.isDone()); assertFalse(transition.isDone());
        workers.remove().run(); await(periodic);
        assertEquals(1, Collections.frequency(events, "save"));
        while (!transition.isDone()) { var task = workers.poll(); assertNotNull(task); task.run(); }
        assertEquals(ProjectLifecycle.Outcome.COMPLETED, await(transition).outcome());
        assertEquals(2, Collections.frequency(events, "save"));
        assertTrue(events.lastIndexOf("save") < events.indexOf("load"));
    }

    @Test void failedResumeRevokesEarlierActivationsAndDoesNotLeaveBusyFlagStuck() throws Exception {
        failResume = true;
        var failed = await(lifecycle.open(directory.resolve("next")));
        assertEquals(ProjectLifecycle.Outcome.FAILED, failed.outcome()); assertNull(lifecycle.context()); assertFalse(lifecycle.busy());
        assertEquals(2, Collections.frequency(events, "stop"));
    }
}
