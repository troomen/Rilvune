package dev.rilvune.document.application;

import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.domain.DocumentStore;
import dev.rilvune.document.domain.DraftRepository;
import dev.rilvune.document.domain.RecoveryDraft;
import dev.rilvune.document.domain.SyncState;
import dev.rilvune.document.domain.TextFormat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Application-level document ownership and external-change observation. Executors belong to the caller. */
public final class DocumentService {
    private final DocumentStore store;
    private final DraftRepository drafts;
    private final ExecutorService io;
    private final Executor notifications;
    private final Map<UUID, DocumentSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, Ownership> consumers = new LinkedHashMap<>();
    private final Map<Path, WatchKey> watchedParents = new LinkedHashMap<>();
    private final WatchService watcher;
    private final ScheduledFuture<?> polling;
    private final ScheduledFuture<?> events;
    private final java.util.List<java.util.function.Consumer<DocumentSnapshot>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Observe session state transitions without acquiring editable ownership. */
    public AutoCloseable addListener(java.util.function.Consumer<DocumentSnapshot> listener) {
        listeners.add(java.util.Objects.requireNonNull(listener)); return () -> listeners.remove(listener);
    }
    private List<DraftRepository.Problem> recoveryProblems = List.of();
    private boolean closed;
    private CompletableFuture<Void> closeOperation;

    public DocumentService(DocumentStore store, DraftRepository drafts, ExecutorService io,
                           ScheduledExecutorService scheduler, Executor notifications, Duration pollInterval) {
        if (pollInterval.isNegative() || pollInterval.isZero()) throw new IllegalArgumentException("Polling interval must be positive");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.drafts = java.util.Objects.requireNonNull(drafts, "drafts");
        this.io = java.util.Objects.requireNonNull(io, "io");
        this.notifications = java.util.Objects.requireNonNull(notifications, "notifications");
        WatchService available;
        try { available = FileSystems.getDefault().newWatchService(); }
        catch (IOException exception) { available = null; }
        watcher = available;
        polling = scheduler.scheduleWithFixedDelay(this::refreshAll, pollInterval.toMillis(),
                Math.max(1, pollInterval.toMillis()), TimeUnit.MILLISECONDS);
        events = scheduler.scheduleWithFixedDelay(this::drainEvents, 120, 120, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<DocumentSession> open(Path requested) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path canonical = store.canonicalPath(requested);
                synchronized (this) {
                    requireOpen();
                    for (DocumentSession session : sessions.values()) if (canonical.equals(session.path())) return session;
                }
                RecoveryDraft candidate = loadRecovery().stream()
                        .filter(draft -> canonical.equals(draft.path())).findFirst().orElse(null);
                RecoveryDraft recovery = candidate == null ? null : claimCurrentRecovery(candidate.id());
                try {
                    if (recovery != null && !canonical.equals(recovery.path())) {
                        releaseUnusedRecovery(recovery.id());
                        recovery = null;
                    }
                    var disk = recovery == null ? store.read(canonical) : null;
                    synchronized (this) {
                        requireOpen();
                        for (DocumentSession session : sessions.values()) if (canonical.equals(session.path())) return session;
                        DocumentSession session = create(recovery != null ? recovery : new RecoveryDraft(UUID.randomUUID(), canonical, disk.text(), 0,
                                disk.format(), disk, disk, SyncState.SYNCHRONIZED, null));
                        watch(session);
                        if (recovery != null) session.refresh();
                        return session;
                    }
                } finally {
                    if (recovery != null) releaseUnusedRecovery(recovery.id());
                }
            } catch (IOException exception) { throw new CompletionException(exception); }
        }, io);
    }

    public DocumentSession createDraft() { return createDraft(TextFormat.utf8Lf()); }

    public synchronized DocumentSession createDraft(TextFormat format) {
        requireOpen();
        DocumentSession session = create(new RecoveryDraft(UUID.randomUUID(), null, "", 0, format,
                null, null, SyncState.DRAFT, null));
        session.refresh();
        return session;
    }

    /** Safe to call again from a Drafts action; already-open identities are skipped. */
    public CompletableFuture<List<DocumentSession>> restoreDrafts() {
        return restoreDrafts(path -> true);
    }

    public CompletableFuture<List<DocumentSession>> restoreDrafts(java.util.function.Predicate<Path> allowedPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<DocumentSession> recovered = new ArrayList<>();
                for (RecoveryDraft candidate : loadRecovery()) {
                    if (candidate.path() != null && !allowedPath.test(candidate.path())) continue;
                    synchronized (this) {
                        requireOpen();
                        if (sessions.containsKey(candidate.id())) continue;
                    }
                    RecoveryDraft draft = claimCurrentRecovery(candidate.id());
                    if (draft == null) continue;
                    try {
                        if (draft.path() != null && !allowedPath.test(draft.path())) continue;
                        synchronized (this) {
                            requireOpen();
                            if (sessions.containsKey(draft.id())) continue;
                            DocumentSession existing = sessions.values().stream()
                                    .filter(session -> draft.path() != null && draft.path().equals(session.path())).findFirst().orElse(null);
                            // An additional recovered version never acquires write ownership of an already-open path.
                            RecoveryDraft toRestore = existing == null ? draft : new RecoveryDraft(draft.id(), null,
                                    draft.text(), draft.revision(), draft.format(), draft.base(), draft.external(), SyncState.DRAFT,
                                    "Recovered additional version of " + draft.path() + ". Save it to a new path to retain this copy.");
                            DocumentSession session = create(toRestore);
                            watch(session);
                            recovered.add(session);
                            session.refresh();
                        }
                    } finally { releaseUnusedRecovery(draft.id()); }
                }
                return List.copyOf(recovered);
            } catch (IOException exception) { throw new CompletionException(exception); }
        }, io);
    }

    public synchronized List<DocumentSession> sessions() { return List.copyOf(sessions.values()); }

    /** Callers explicitly retain a session when acquiring a lasting document view. */
    public synchronized DocumentHandle retain(DocumentSession session) {
        requireOpen();
        if (sessions.get(session.id()) != session || session.isClosed() || session.isClosing()) {
            throw new IllegalStateException("Document is unavailable or closing");
        }
        Ownership ownership = consumers.computeIfAbsent(session.id(), ignored -> new Ownership());
        if (ownership.releasing) throw new IllegalStateException("Document's final consumer is closing");
        ownership.count++;
        return new DocumentHandle(session, () -> release(session));
    }
    public CompletableFuture<DocumentHandle> acquire(Path path) { return open(path).thenApply(this::retain); }

    private CompletableFuture<Void> release(DocumentSession session) {
        Ownership ownership;
        synchronized (this) {
            ownership = consumers.get(session.id());
            if (session.isClosed() || ownership == null) return CompletableFuture.completedFuture(null);
            if (ownership.count > 1) {
                ownership.count--;
                return CompletableFuture.completedFuture(null);
            }
            ownership.releasing = true;
        }
        return session.close().whenComplete((ignored, failure) -> {
            synchronized (this) {
                // Recovery may already have reused the UUID for a different session/ownership.
                if (failure == null) consumers.remove(session.id(), ownership);
                else if (consumers.get(session.id()) == ownership) ownership.releasing = false;
            }
        });
    }

    public void refreshAll() {
        for (DocumentSession session : sessions()) session.refresh();
    }

    public CompletableFuture<List<DocumentSnapshot>> flushAll() {
        List<CompletableFuture<DocumentSnapshot>> writes = sessions().stream().map(DocumentSession::flush).toList();
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> writes.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<Void> close() {
        CompletableFuture<Void> completion;
        List<DocumentSession> pending;
        synchronized (this) {
            if (closed) return CompletableFuture.completedFuture(null);
            if (closeOperation != null) return closeOperation;
            completion = new CompletableFuture<>();
            closeOperation = completion;
            pending = List.copyOf(sessions.values());
        }
        // Do not hold the ownership monitor while closing sessions: their callbacks remove themselves.
        CompletableFuture.allOf(pending.stream().map(DocumentSession::close).toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> {
                    synchronized (this) {
                        if (failure == null) closed = true;
                        else closeOperation = null; // Recovery errors are retryable; keep observing remaining documents.
                    }
                    if (failure != null) { completion.completeExceptionally(failure); return; }
                    listeners.clear();
                    polling.cancel(false);
                    events.cancel(false);
                    if (watcher != null) try { watcher.close(); } catch (IOException ignoredClose) { /* Observation has stopped. */ }
                    completion.complete(null);
                });
        return completion;
    }

    /** Last discovery/read warnings, including records that were deliberately left untouched. */
    public synchronized List<DraftRepository.Problem> recoveryProblems() { return recoveryProblems; }

    private List<RecoveryDraft> loadRecovery() {
        DraftRepository.Scan scan;
        try { scan = drafts.scan(); }
        catch (IOException failure) { scan = new DraftRepository.Scan(List.of(), List.of(new DraftRepository.Problem(null, failure.getMessage()))); }
        synchronized (this) { recoveryProblems = scan.problems(); }
        return scan.drafts();
    }

    private synchronized void recoveryProblem(IOException failure) {
        var problems = new ArrayList<>(recoveryProblems);
        var problem = new DraftRepository.Problem(null, failure.getMessage());
        if (!problems.contains(problem)) problems.add(problem);
        recoveryProblems = List.copyOf(problems);
    }

    /** The initial scan is advisory: the previous owner may finish another write before releasing its lock. */
    private RecoveryDraft claimCurrentRecovery(UUID id) throws IOException {
        if (!drafts.claim(id)) return null;
        try {
            RecoveryDraft current;
            try { current = drafts.find(id).orElse(null); }
            catch (IOException unreadable) {
                recoveryProblem(unreadable);
                releaseUnusedRecovery(id);
                return null;
            }
            if (current == null) releaseUnusedRecovery(id);
            return current;
        } catch (IOException | RuntimeException failure) {
            try { releaseUnusedRecovery(id); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private synchronized void releaseUnusedRecovery(UUID id) throws IOException {
        if (!sessions.containsKey(id)) drafts.release(id);
    }

    private DocumentSession create(RecoveryDraft draft) {
        DocumentSession session = new DocumentSession(draft, store, drafts, io, notifications, this::remove, this::pathChanged);
        session.addListener(snapshot -> {
            for (var listener : listeners) try { listener.accept(snapshot); }
            catch (RuntimeException failure) {
                System.getLogger(DocumentService.class.getName()).log(System.Logger.Level.WARNING, "Document service observer failed", failure);
            }
        });
        sessions.put(session.id(), session);
        return session;
    }

    private synchronized void remove(DocumentSession session) {
        sessions.remove(session.id());
        consumers.remove(session.id());
    }

    private static final class Ownership {
        private int count;
        private boolean releasing;
    }

    private synchronized void pathChanged(DocumentSession session) { watch(session); }

    private synchronized void watch(DocumentSession session) {
        if (watcher == null || closed || session.path() == null) return;
        Path parent = session.path().getParent();
        if (parent == null || watchedParents.containsKey(parent)) return;
        try {
            watchedParents.put(parent, parent.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE));
        } catch (IOException ignored) { /* Periodic content verification remains active. */ }
    }

    private void drainEvents() {
        if (watcher == null) return;
        try {
            WatchKey key;
            while ((key = watcher.poll()) != null) {
                Path parent = (Path) key.watchable();
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) { refreshAll(); continue; }
                    if (!(event.context() instanceof Path child)) continue;
                    Path changed = parent.resolve(child);
                    for (DocumentSession session : sessions()) if (changed.equals(session.path())) session.refresh();
                }
                if (!key.reset()) synchronized (this) { watchedParents.remove(parent); }
            }
        } catch (java.nio.file.ClosedWatchServiceException ignored) { /* Normal shutdown. */ }
    }

    private void requireOpen() { if (closed || closeOperation != null) throw new IllegalStateException("Document service is closing or closed"); }
}
