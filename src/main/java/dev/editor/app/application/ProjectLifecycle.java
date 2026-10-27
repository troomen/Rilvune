package dev.editor.app.application;

import dev.editor.session.domain.SessionState;
import dev.editor.session.domain.SessionStore;
import dev.editor.workspace.domain.ProjectContext;
import dev.editor.workspace.domain.Workspace;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Window-independent sequencing. Entry points and continuations run on the supplied control executor. */
public final class ProjectLifecycle {
    public enum Outcome { COMPLETED, CANCELLED, BUSY, FAILED }
    public enum Stage { STARTING, QUIESCING, VALIDATING, SAVING, CLOSING_VIEWS, RESTORING, CLOSING_DOCUMENTS, FINISHING, READY }
    public record Result(Outcome outcome, Stage stage, Workspace workspace, ProjectContext context, Throwable failure) { }
    public interface Participant {
        String name();
        CompletableFuture<Void> quiesce();
        void resume(ProjectContext context);
    }
    public interface Host {
        void transitioning(boolean active);
        SessionState capture();
        CompletableFuture<Boolean> closeViews();
        void prepareRestore();
        CompletableFuture<Void> restore(Workspace workspace, SessionState state, boolean bounds);
        void publish(Workspace workspace);
        void warning(Throwable failure);
        CompletableFuture<Void> finish();
    }
    @FunctionalInterface public interface WorkspaceLoader { Workspace load(Path path) throws Exception; }

    private enum Kind { INITIALIZE, OPEN, CLOSE, EXIT, SNAPSHOT }
    private final Host host;
    private final SessionStore sessions;
    private final WorkspaceLoader loader;
    private final Supplier<CompletableFuture<Void>> recover, closeDocuments;
    private final List<Participant> participants;
    private final Executor control, io;
    private volatile Workspace workspace;
    private volatile ProjectContext context;
    private volatile boolean busy, closed;
    private Stage stage = Stage.READY;
    private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);

    public ProjectLifecycle(Workspace initial, SessionStore sessions, WorkspaceLoader loader, Host host,
                            List<Participant> participants, Supplier<CompletableFuture<Void>> recover,
                            Supplier<CompletableFuture<Void>> closeDocuments, Executor control, Executor io) {
        workspace = initial;
        this.sessions = Objects.requireNonNull(sessions);
        this.loader = Objects.requireNonNull(loader);
        this.host = Objects.requireNonNull(host);
        this.participants = List.copyOf(participants);
        this.recover = Objects.requireNonNull(recover);
        this.closeDocuments = Objects.requireNonNull(closeDocuments);
        this.control = Objects.requireNonNull(control);
        this.io = Objects.requireNonNull(io);
    }
    public ProjectContext context() { return context; }
    public boolean busy() { return busy; }
    public CompletableFuture<Result> initialize() { return request(Kind.INITIALIZE, null); }
    public CompletableFuture<Result> open(Path path) { return request(Kind.OPEN, Objects.requireNonNull(path)); }
    public CompletableFuture<Result> closeProject() { return request(Kind.CLOSE, null); }
    public CompletableFuture<Result> exit() { return request(Kind.EXIT, null); }
    public CompletableFuture<Result> finishSnapshot() { return request(Kind.SNAPSHOT, null); }

    /** Periodic saves share the ordered chain used by transitions. */
    public CompletableFuture<Void> persist() {
        return onControl(() -> !busy && !closed ? save(host.capture()) : CompletableFuture.<Void>completedFuture(null))
                .thenCompose(future -> future).copy();
    }

    private CompletableFuture<Result> request(Kind kind, Path path) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        onControl(() -> {
            if (busy || closed) { result.complete(result(Outcome.BUSY, null)); return null; }
            busy = true; context = null; stage = Stage.STARTING;
            Workspace before = workspace;
            CompletableFuture<Boolean> operation;
            try {
                host.transitioning(true);
                operation = run(kind, path);
            } catch (Exception failure) { operation = CompletableFuture.failedFuture(failure); }
            operation.whenComplete((accepted, failure) -> onControl(() -> {
                result.complete(finishOperation(kind, before, accepted, failure));
                return null;
            }).exceptionally(dispatchFailure -> {
                context = null; busy = false;
                result.complete(result(Outcome.FAILED, root(dispatchFailure))); return null;
            }));
            return null;
        }).exceptionally(failure -> { result.complete(result(Outcome.FAILED, root(failure))); return null; });
        return result.copy();
    }

    private Result finishOperation(Kind kind, Workspace before, Boolean accepted, Throwable failure) {
        Throwable error = root(failure);
        Stage completedStage = stage;
        try {
            if (error == null) {
                if ((kind == Kind.EXIT || kind == Kind.SNAPSHOT) && Boolean.TRUE.equals(accepted)) closed = true;
                else resume();
            } else if (stage == Stage.VALIDATING || stage == Stage.SAVING) {
                workspace = before; // No views have been closed; cleanup has already completed.
                resume();
            } else if (stage == Stage.RESTORING) {
                host.publish(workspace); // Show the actual partial workspace without enabling background edits.
            }
        } catch (Exception recoveryFailure) {
            context = null;
            if (error == null) error = recoveryFailure;
            else error.addSuppressed(recoveryFailure);
        } finally {
            busy = false;
            try { host.transitioning(false); }
            catch (Exception hostFailure) { if (error == null) error = hostFailure; else error.addSuppressed(hostFailure); }
        }
        Outcome outcome = error != null ? Outcome.FAILED : Boolean.TRUE.equals(accepted) ? Outcome.COMPLETED : Outcome.CANCELLED;
        return new Result(outcome, error == null ? stage : completedStage, workspace, context, error);
    }

    private CompletableFuture<Boolean> run(Kind kind, Path path) {
        if (kind == Kind.INITIALIZE) return after(call(recover), ignored -> restore(workspace, true)).thenApply(ignored -> true);
        return after(quiesce(), ignored -> {
            stage = Stage.VALIDATING;
            CompletableFuture<Workspace> target = kind == Kind.OPEN ? supplyIo(() -> loader.load(path)) : CompletableFuture.completedFuture(workspace);
            return after(target, next -> saveAndClose(kind, next));
        });
    }

    private CompletableFuture<Void> quiesce() {
        stage = Stage.QUIESCING;
        List<CompletableFuture<Void>> stops = new ArrayList<>();
        for (Participant participant : participants) {
            stops.add(call(participant::quiesce).handle((ignored, failure) -> {
                if (failure != null) throw new CompletionException(new IllegalStateException(
                        "Could not stop " + participant.name() + "; retry the project operation", root(failure)));
                return null;
            }));
        }
        return CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Boolean> saveAndClose(Kind kind, Workspace next) {
        stage = Stage.SAVING;
        return after(save(host.capture()), ignored -> {
            stage = Stage.CLOSING_VIEWS;
            var decision = kind == Kind.SNAPSHOT ? CompletableFuture.completedFuture(true) : call(host::closeViews);
            return after(decision, accepted -> accepted ? completeTransition(kind, next) : CompletableFuture.completedFuture(false));
        });
    }

    private CompletableFuture<Boolean> completeTransition(Kind kind, Workspace next) {
        if (kind == Kind.OPEN) return restore(next, false).thenApply(ignored -> true);
        if (kind == Kind.CLOSE) {
            workspace = null; host.publish(null);
            return CompletableFuture.completedFuture(true);
        }
        stage = Stage.CLOSING_DOCUMENTS;
        return after(call(closeDocuments), ignored -> {
            stage = Stage.FINISHING;
            return call(host::finish).thenApply(done -> true);
        });
    }

    private <T, U> CompletableFuture<U> after(CompletableFuture<T> previous,
                                               java.util.function.Function<T, CompletableFuture<U>> next) {
        return previous.thenCompose(value -> onControl(() -> next.apply(value))).thenCompose(future -> future);
    }

    private CompletableFuture<Void> restore(Workspace next, boolean bounds) {
        stage = Stage.RESTORING;
        if (next == null) { workspace = null; host.publish(null); return CompletableFuture.completedFuture(null); }
        // After old views close, failures belong to the new, possibly partial workspace.
        workspace = next;
        host.prepareRestore();
        return supplyIo(() -> {
            sessions.remember(next.root());
            try { return sessions.load(next.root()); }
            catch (Exception failure) {
                onControl(() -> { host.warning(failure); return null; });
                return SessionState.empty(next.root());
            }
        }).thenCompose(saved -> onControl(() -> call(() -> host.restore(next, saved, bounds))))
                .thenCompose(future -> future).thenCompose(ignored -> onControl(() -> { host.publish(next); return null; }));
    }

    private void resume() {
        ProjectContext next = workspace == null ? null : ProjectContext.activate(workspace);
        try { for (Participant participant : participants) participant.resume(next); }
        catch (RuntimeException failure) {
            context = null;
            // Revoke any earlier activation too; each participant retains its own pending cleanup.
            for (Participant participant : participants) call(participant::quiesce).exceptionally(cleanup -> {
                System.getLogger(ProjectLifecycle.class.getName()).log(System.Logger.Level.WARNING, "Activation cleanup failed", root(cleanup));
                return null;
            });
            throw failure;
        }
        context = next;
        stage = Stage.READY;
    }

    private Result result(Outcome outcome, Throwable failure) { return new Result(outcome, stage, workspace, context, failure); }
    private CompletableFuture<Void> save(SessionState state) {
        if (state == null) return writes;
        writes = writes.handle((ignored, failure) -> null).thenCompose(ignored -> supplyIo(() -> { sessions.save(state); return null; }));
        return writes;
    }
    private <T> CompletableFuture<T> onControl(Supplier<T> action) {
        try { return CompletableFuture.supplyAsync(action, control); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @FunctionalInterface private interface IoTask<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> supplyIo(IoTask<T> task) {
        try { return CompletableFuture.supplyAsync(() -> { try { return task.run(); } catch (Exception failure) { throw new CompletionException(failure); } }, io); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> action) {
        try { return Objects.requireNonNull(action.get()); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private static Throwable root(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
