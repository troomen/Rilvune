package dev.rilvune.search.application;

import dev.rilvune.document.application.DocumentHandle;
import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.domain.SyncState;
import dev.rilvune.platform.text.domain.LiteralText;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static dev.rilvune.search.application.ReplacementProblem.Phase;
import static dev.rilvune.search.application.ReplacementResult.BufferEffect;
import static dev.rilvune.search.application.ReplacementResult.Persistence;

/** Window-owned replacement operations. Only handle release can end a consumer's ownership. */
public final class ProjectReplacement {
    private enum State { PREPARING, READY, APPLYING, RELEASING, CLEANUP_FAILED, RELEASED }
    public record FileSummary(Path path, long revision, int matches) { }

    /** Single-use editing capability; the UI can inspect summaries, never live sessions or handles. */
    public static final class Plan {
        private final ProjectReplacement owner;
        private final ReplacementHandles handles = new ReplacementHandles();
        private final List<Target> targets = new ArrayList<>();
        private final IdentityHashMap<DocumentSession, Target> sessions = new IdentityHashMap<>();
        private final CompletableFuture<Void> settled = new CompletableFuture<>();
        private volatile List<FileSummary> files = List.of();
        private volatile Path preparingPath;
        private State state = State.PREPARING;
        private volatile boolean abandoned;
        private CompletableFuture<Void> cleanup;
        private ReplacementFailure cleanupFailure;

        private Plan(ProjectReplacement owner) { this.owner = owner; }
        public List<FileSummary> files() { return files; }
        public int matches() { return files.stream().mapToInt(FileSummary::matches).sum(); }
        public long changedFiles() { return files.stream().filter(file -> file.matches() > 0).count(); }
        public boolean ready() { synchronized (owner) { return state == State.READY && !abandoned; } }
    }

    private static final class Target {
        private final DocumentHandle handle;
        private final DocumentSession document;
        private final Path path;
        private final long revision;
        private final int matches;
        private String text;
        private BufferEffect effect;
        private Persistence persistence = Persistence.NOT_APPLICABLE;

        private Target(DocumentHandle handle, DocumentSnapshot snapshot, String text, int matches) {
            this.handle = handle;
            document = handle.session();
            path = snapshot.path();
            revision = snapshot.revision();
            this.text = text;
            this.matches = matches;
            effect = matches == 0 ? BufferEffect.NO_MATCH : BufferEffect.NOT_ATTEMPTED;
        }
        private ReplacementProblem problem(Phase phase, Throwable failure) {
            return new ReplacementProblem(phase, path, document.path(), document.id(), failure);
        }
        private boolean current() {
            synchronized (document) {
                return Objects.equals(path, document.path()) && revision == document.revision() && available(document);
            }
        }
        private ReplacementResult.FileResult result() {
            return new ReplacementResult.FileResult(path, document.path(), document.id(), matches, effect, persistence, handle.isReleased());
        }
    }

    private final DocumentService documents;
    private final Executor editingExecutor;
    private final Executor computationExecutor;
    private final Runnable cleanupChanged;
    // Admission and terminal state are synchronized so rejected executor dispatch cannot lose ownership.
    // Capturing and applying use the editing executor; pure text calculation uses the worker.
    private Plan active;
    private boolean accepting = true;

    public ProjectReplacement(DocumentService documents, Executor editingExecutor) {
        this(documents, editingExecutor, () -> { });
    }

    public ProjectReplacement(DocumentService documents, Executor editingExecutor, Runnable cleanupChanged) {
        this(documents, editingExecutor, editingExecutor, cleanupChanged);
    }

    public ProjectReplacement(DocumentService documents, Executor editingExecutor, Executor computationExecutor, Runnable cleanupChanged) {
        this.computationExecutor = Objects.requireNonNull(computationExecutor);
        this.documents = Objects.requireNonNull(documents);
        this.editingExecutor = Objects.requireNonNull(editingExecutor);
        this.cleanupChanged = Objects.requireNonNull(cleanupChanged);
    }

    public CompletableFuture<Plan> prepare(List<ProjectSearch.Hit> hits, String query, String replacement,
                                           boolean matchCase, boolean single) {
        final List<ProjectSearch.Hit> request;
        try {
            request = List.copyOf(hits);
            Objects.requireNonNull(query);
            Objects.requireNonNull(replacement);
            if (query.isEmpty()) throw new IllegalArgumentException("Enter text to replace");
            if (single && request.size() != 1) throw new IllegalArgumentException("Select exactly one match to replace");
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        Plan plan;
        synchronized (this) {
            if (!accepting || active != null) return CompletableFuture.failedFuture(new IllegalStateException(
                    "Finish the current replacement or retry its cleanup before preparing another"));
            plan = new Plan(this);
            active = plan;
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Path path : request.stream().map(ProjectSearch.Hit::path).distinct().toList()) {
            chain = chain.thenCompose(ignored -> acquire(plan, path, request, query, replacement, matchCase, single));
        }
        CompletableFuture<Plan> prepared = new CompletableFuture<>();
        chain.whenComplete((ignored, failure) -> {
            boolean publish;
            synchronized (this) {
                publish = failure == null && !plan.abandoned;
                if (publish) {
                    plan.files = plan.targets.stream().map(target -> new FileSummary(target.path, target.revision, target.matches)).toList();
                    plan.state = State.READY;
                }
            }
            if (publish) { prepared.complete(plan); return; }
            Throwable primary = failure == null ? new CancellationException("Replacement preparation was abandoned") : failure;
            var problem = new ReplacementProblem(Phase.PREPARATION, plan.preparingPath, null, null, primary);
            cleanup(plan).whenComplete((done, cleanupFailure) -> {
                var problems = new ArrayList<>(List.of(problem));
                collect(problems, cleanupFailure, Phase.CLEANUP);
                prepared.completeExceptionally(new ReplacementFailure(problems));
            });
        });
        return prepared.copy();
    }

    private CompletableFuture<Void> acquire(Plan plan, Path path, List<ProjectSearch.Hit> hits, String query,
                                             String replacement, boolean matchCase, boolean single) {
        synchronized (this) {
            if (plan.abandoned) return CompletableFuture.failedFuture(new CancellationException("Replacement preparation was abandoned"));
            plan.preparingPath = path;
        }
        return documents.acquire(path).thenCompose(handle -> {
            // This runs before any dispatch or validation that could fail. The internal chain is never cancelled.
            plan.handles.add(handle, path);
            return prepareTarget(plan, handle, hits, query, replacement, matchCase, single)
                    .thenCompose(ignored -> plan.sessions.get(handle.session()).handle == handle
                            ? CompletableFuture.completedFuture(null) : plan.handles.release(handle));
        });
    }

    private CompletableFuture<Void> prepareTarget(Plan plan, DocumentHandle handle, List<ProjectSearch.Hit> hits,
                                                   String query, String replacement, boolean matchCase, boolean single) {
        return dispatch(() -> {
            if (plan.abandoned) throw new CancellationException("Replacement preparation was abandoned");
            DocumentSession document = handle.session();
            if (plan.sessions.containsKey(document)) return null;
            if (!available(document)) throw new IllegalStateException("Resolve the document before replacing: " + document.path());
            return document.snapshot();
        }, editingExecutor).thenCompose(snapshot -> {
            if (snapshot == null) return CompletableFuture.completedFuture(null);
            return dispatch(() -> {
                if (plan.abandoned) throw new CancellationException("Replacement preparation was abandoned");
                var needle = new LiteralText.Query(query, matchCase, false);
                var result = single
                        ? new LiteralText.Replacement(LiteralText.replaceAt(snapshot.text(), needle, replacement,
                                hits.getFirst().offset(), LiteralText.MAX_RESULT_CHARACTERS), 1)
                        : LiteralText.replace(snapshot.text(), needle, replacement, 100000,
                                LiteralText.MAX_RESULT_CHARACTERS, () -> plan.abandoned);
                return new Target(handle, snapshot, result.text(), result.matches());
            }, computationExecutor).thenCompose(target -> onEditing(() -> {
                if (plan.abandoned) throw new CancellationException("Replacement preparation was abandoned");
                plan.targets.add(target);
                plan.sessions.put(handle.session(), target);
            }));
        });
    }

    private static <T> CompletableFuture<T> dispatch(java.util.function.Supplier<T> action, Executor executor) {
        try { return CompletableFuture.supplyAsync(action, executor); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    public CompletableFuture<ReplacementResult> apply(Plan plan) {
        synchronized (this) {
            if (!owns(plan) || plan.state != State.READY || plan.abandoned) return CompletableFuture.failedFuture(
                    new IllegalStateException("Replacement plan is foreign, consumed or no longer ready"));
            plan.state = State.APPLYING;
        }
        List<ReplacementProblem> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<ReplacementResult> result = new CompletableFuture<>();
        onEditing(() -> edit(plan, problems)).handle((ignored, failure) -> {
            collect(problems, failure, Phase.APPLICATION);
            return null;
        }).thenCompose(ignored -> flush(plan, problems)).whenComplete((ignored, failure) -> {
            collect(problems, failure, Phase.PERSISTENCE);
            cleanup(plan).whenComplete((done, cleanupFailure) -> {
                collect(problems, cleanupFailure, Phase.CLEANUP);
                result.complete(new ReplacementResult(plan.targets.stream().map(Target::result).toList(), problems));
            });
        });
        return result.copy();
    }

    private void edit(Plan plan, List<ReplacementProblem> problems) {
        for (Target target : plan.targets) {
            if (!target.current()) {
                target.effect = BufferEffect.REJECTED;
                problems.add(target.problem(Phase.PREFLIGHT, new IOException("Document changed since preparation. Search again.")));
            }
        }
        if (!problems.isEmpty()) return;
        for (Target target : plan.targets) {
            if (target.matches == 0) continue;
            target.effect = BufferEffect.UNCERTAIN;
            try {
                var change = target.document.changeIfCurrent(target.revision, target.path, target.text);
                if (!change.accepted()) {
                    target.effect = BufferEffect.REJECTED;
                    problems.add(target.problem(Phase.APPLICATION, new IOException("Document changed after preflight; later files were not edited.")));
                    break;
                }
                target.effect = BufferEffect.APPLIED;
                if (change.deliveryFailure() != null) problems.add(target.problem(Phase.APPLICATION, change.deliveryFailure()));
                if (change.schedulingFailure() != null) {
                    problems.add(target.problem(Phase.APPLICATION, change.schedulingFailure()));
                    break;
                }
            } catch (RuntimeException failure) {
                synchronized (target.document) {
                    // Scheduling may throw after the buffer was changed. Count a verifiable edit;
                    // otherwise keep UNCERTAIN and still settle this document's persistence.
                    if (Objects.equals(target.path, target.document.path())
                            && target.document.revision() == target.revision + 1
                            && target.text.equals(target.document.text())) target.effect = BufferEffect.APPLIED;
                }
                problems.add(target.problem(Phase.APPLICATION, failure));
                break;
            }
        }
    }

    private CompletableFuture<Void> flush(Plan plan, List<ReplacementProblem> problems) {
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (Target target : plan.targets) {
            if (target.effect != BufferEffect.APPLIED && target.effect != BufferEffect.UNCERTAIN) continue;
            CompletableFuture<DocumentSnapshot> write;
            try { write = target.document.flush(); }
            catch (RuntimeException failure) { write = CompletableFuture.failedFuture(failure); }
            writes.add(write.handle((snapshot, failure) -> {
                if (failure != null) {
                    target.persistence = Persistence.FAILED;
                    problems.add(target.problem(Phase.PERSISTENCE, failure));
                } else if (snapshot.state() != SyncState.SYNCHRONIZED) {
                    target.persistence = Persistence.NEEDS_ATTENTION;
                    problems.add(target.problem(Phase.PERSISTENCE, new IOException(
                            snapshot.message() == null ? "Document still needs synchronization" : snapshot.message())));
                } else target.persistence = Persistence.SYNCHRONIZED;
                return null;
            }));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    public CompletableFuture<Void> release(Plan plan) {
        synchronized (this) {
            if (!owns(plan)) return CompletableFuture.failedFuture(new IllegalArgumentException("Foreign replacement plan"));
            if (plan.state == State.PREPARING || plan.state == State.APPLYING) return plan.settled.copy();
            plan.abandoned = true;
        }
        return cleanup(plan);
    }

    public synchronized boolean cleanupPending() { return active != null && active.state == State.CLEANUP_FAILED; }

    public synchronized List<ReplacementProblem> cleanupProblems() {
        return active == null || active.cleanupFailure == null ? List.of() : active.cleanupFailure.problems();
    }

    public CompletableFuture<Void> retryCleanup() {
        Plan plan;
        synchronized (this) {
            plan = active;
            if (plan == null) return CompletableFuture.completedFuture(null);
            if (plan.state != State.CLEANUP_FAILED && plan.state != State.RELEASING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Replacement is still active"));
            }
        }
        return cleanup(plan);
    }

    /** Stops admission and drains arrivals, active application and retryable cleanup before document shutdown. */
    public CompletableFuture<Void> quiesce() {
        Plan plan;
        synchronized (this) {
            accepting = false;
            plan = active;
            if (plan == null) return CompletableFuture.completedFuture(null);
            plan.abandoned = true;
            if (plan.state == State.PREPARING || plan.state == State.APPLYING) return plan.settled.copy();
        }
        return cleanup(plan);
    }

    public synchronized void resume() { accepting = true; }

    private CompletableFuture<Void> cleanup(Plan plan) {
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (plan.state == State.RELEASED) return CompletableFuture.completedFuture(null);
            if (plan.state == State.RELEASING) return plan.cleanup.copy();
            plan.state = State.RELEASING;
            completion = new CompletableFuture<>();
            plan.cleanup = completion;
        }
        plan.handles.releaseAll().whenComplete((ignored, failure) -> {
            // Lifecycle bookkeeping cannot depend on an editor executor that may have rejected a continuation.
            synchronized (this) {
                plan.state = failure == null ? State.RELEASED : State.CLEANUP_FAILED;
                plan.cleanupFailure = failure == null ? null : (ReplacementFailure) ReplacementProblem.unwrap(failure);
                plan.targets.forEach(target -> target.text = null);
                if (failure == null && active == plan) active = null;
            }
            try { cleanupChanged.run(); } catch (RuntimeException ignoredObserver) { /* Observation cannot change ownership. */ }
            if (failure == null) {
                completion.complete(null);
                plan.settled.complete(null);
            } else {
                completion.completeExceptionally(failure);
                plan.settled.completeExceptionally(failure);
            }
        });
        return completion.copy();
    }

    private boolean owns(Plan plan) { return plan != null && plan.owner == this; }

    private CompletableFuture<Void> onEditing(Runnable action) {
        try { return CompletableFuture.runAsync(action, editingExecutor); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private static boolean available(DocumentSession document) {
        return !document.isClosed() && !document.isClosing() && !document.isPaused() && !document.snapshot().needsAttention();
    }

    private static void collect(List<ReplacementProblem> problems, Throwable failure, Phase phase) {
        if (failure == null) return;
        Throwable cause = ReplacementProblem.unwrap(failure);
        if (cause instanceof ReplacementFailure aggregate) problems.addAll(aggregate.problems());
        else problems.add(new ReplacementProblem(phase, null, null, null, cause));
    }
}
