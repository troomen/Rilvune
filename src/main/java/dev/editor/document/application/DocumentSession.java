package dev.editor.document.application;

import dev.editor.document.domain.DiskSnapshot;
import dev.editor.document.domain.DocumentSnapshot;
import dev.editor.document.domain.DocumentChange;
import dev.editor.document.domain.DocumentStore;
import dev.editor.document.domain.DraftRepository;
import dev.editor.document.domain.FileChangedException;
import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.domain.SyncState;
import dev.editor.document.domain.TextFormat;
import dev.editor.document.domain.TextMerge;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** A mutable text buffer independent of Swing, with one ordered persistence lane. */
public final class DocumentSession {
    private final UUID id;
    private final DocumentStore store;
    private final DraftRepository drafts;
    private final SerialExecutor io;
    private final Executor notifications;
    private final Consumer<DocumentSession> onClose;
    private final Consumer<DocumentSession> onPathChanged;
    private final StringBuilder buffer;
    private final List<Consumer<DocumentSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<DocumentSnapshot>> waiters = new ArrayList<>();
    private Path path;
    private long revision;
    private String cachedText;
    private long cachedRevision;
    private TextFormat format;
    private DiskSnapshot base;
    private DiskSnapshot external;
    private SyncState state;
    private String message;
    private boolean conflictLocked;
    private enum Suspension { NONE, FILE_OPERATION, RECOVERY_REVIEW }
    private Suspension suspension;
    private boolean scheduled;
    private boolean closed;
    private boolean closing;
    private CompletableFuture<Void> closeOperation;
    private boolean recoveryDirty;
    private IOException recoveryFailure;

    DocumentSession(RecoveryDraft initial, DocumentStore store, DraftRepository drafts, Executor io,
                    Executor notifications, Consumer<DocumentSession> onClose, Consumer<DocumentSession> onPathChanged) {
        this.id = initial.id();
        this.path = initial.path();
        this.buffer = new StringBuilder(initial.text());
        this.cachedText = initial.text();
        this.revision = initial.revision();
        this.cachedRevision = revision;
        this.format = initial.format();
        this.base = initial.base();
        this.external = initial.external();
        this.state = initial.state();
        this.message = initial.message();
        this.suspension = state == SyncState.PAUSED ? Suspension.RECOVERY_REVIEW : Suspension.NONE;
        this.conflictLocked = state == SyncState.CONFLICT || state == SyncState.DELETED;
        this.store = store;
        this.drafts = drafts;
        this.io = new SerialExecutor(io);
        this.notifications = notifications;
        this.onClose = onClose;
        this.onPathChanged = onPathChanged;
    }

    public UUID id() { return id; }
    public synchronized Path path() { return path; }
    public synchronized long revision() { return revision; }
    public synchronized int textLength() { return buffer.length(); }
    public synchronized SyncState state() { return state; }
    public synchronized boolean isClosed() { return closed; }
    public synchronized boolean isClosing() { return closing; }
    public synchronized boolean isPaused() { return suspension != Suspension.NONE; }
    public synchronized String text() {
        if (cachedRevision != revision) { cachedText = buffer.toString(); cachedRevision = revision; }
        return cachedText;
    }

    public synchronized DocumentSnapshot snapshot() {
        return new DocumentSnapshot(id, path, text(), revision, state, format,
                external == null ? null : external.text(), message, recoveryFailure == null ? null : (recoveryFailure.getMessage() == null ? recoveryFailure.toString() : recoveryFailure.getMessage()), suspension == Suspension.RECOVERY_REVIEW);
    }

    public AutoCloseable addListener(Consumer<DocumentSnapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /** Offset and removed length refer to normalized LF text. No whole-buffer copy per keypress. */
    public synchronized void replace(int offset, int removedLength, String inserted) {
        requireOpen();
        Objects.requireNonNull(inserted, "inserted");
        if (offset < 0 || removedLength < 0 || offset + removedLength > buffer.length()) {
            throw new IndexOutOfBoundsException("Invalid edit range");
        }
        if (removedLength == 0 && inserted.isEmpty()) return;
        buffer.replace(offset, offset + removedLength, inserted);
        revision++;
        recoveryDirty = true;
        if (!conflictLocked && !isPaused()) state = SyncState.SYNCHRONIZING;
        schedule();
    }

    public synchronized void replaceText(String text) {
        requireOpen();
        if (text().equals(text)) return;
        replace(0, buffer.length(), text);
    }

    private DocumentChange.Rejection rejection(long expected, Path expectedPath) {
        if (closed || closing) return DocumentChange.Rejection.CLOSED;
        if (revision != expected || !Objects.equals(path, expectedPath)) return DocumentChange.Rejection.STALE_DOCUMENT;
        if (isPaused()) return DocumentChange.Rejection.PAUSED;
        if (conflictLocked) return DocumentChange.Rejection.NEEDS_RESOLUTION;
        if (snapshot().needsAttention()) return DocumentChange.Rejection.NEEDS_ATTENTION;
        return DocumentChange.Rejection.NONE;
    }

    /** Used by synchronous view commands already running on the editing executor. */
    public synchronized boolean editIfRevision(long expected, Runnable edit) {
        if (rejection(expected, path) != DocumentChange.Rejection.NONE) return false;
        edit.run();
        return true;
    }

    /** Caller runs on the editing/notification executor; model delivery happens before it yields. */
    public DocumentChange changeIfCurrent(long expected, Path expectedPath, String text) {
        Objects.requireNonNull(text, "text");
        DocumentSnapshot changed;
        RuntimeException scheduling = null;
        boolean different;
        synchronized (this) {
            var rejection = rejection(expected, expectedPath);
            if (rejection != DocumentChange.Rejection.NONE) return new DocumentChange(
                    DocumentChange.Effect.REJECTED, rejection, snapshot(), null, null);
            different = !text().equals(text);
            if (different) {
                setText(text);
                state = SyncState.SYNCHRONIZING;
                try { schedule(); } catch (RuntimeException failure) { scheduling = failure; }
            }
            changed = snapshot();
        }
        Throwable delivery = notifyListeners(changed);
        return new DocumentChange(different ? DocumentChange.Effect.APPLIED : DocumentChange.Effect.UNCHANGED,
                DocumentChange.Rejection.NONE, changed, delivery, scheduling);
    }

    public synchronized CompletableFuture<DocumentSnapshot> flush() {
        if (closed) return CompletableFuture.completedFuture(snapshot());
        CompletableFuture<DocumentSnapshot> future = new CompletableFuture<>();
        waiters.add(future);
        try { schedule(); } catch (RuntimeException failure) { future.completeExceptionally(failure); }
        return future.copy();
    }

    public synchronized void refresh() {
        if (!closed && !closing) try { schedule(); } catch (RuntimeException failure) { log("Refresh dispatch failed", failure); }
    }

    public CompletableFuture<DocumentSnapshot> acceptDisk() {
        DiskSnapshot observed;
        long chosenRevision;
        synchronized (this) { observed = external; chosenRevision = revision; }
        return action(() -> {
            Path currentPath;
            synchronized (this) { currentPath = requirePath(); }
            DiskSnapshot current = store.read(currentPath);
            if (observed != null && !observed.fingerprint().equals(current.fingerprint())) throw new FileChangedException(currentPath);
            if (!applyReconciledText(chosenRevision, current.text(), current, true)) {
                throw new IOException("The editor text changed while loading the disk version. Review it and choose again.");
            }
            persistRecovery();
        });
    }

    /** Explicit overwrite uses the version the user actually observed, never a refreshed implicit choice. */
    public CompletableFuture<DocumentSnapshot> overwriteDisk() {
        DiskSnapshot observed;
        synchronized (this) { observed = external; }
        return action(() -> {
            Path target;
            String content;
            TextFormat selectedFormat;
            synchronized (this) { target = requirePath(); content = text(); selectedFormat = format; }
            DiskSnapshot source;
            synchronized (this) { source = base; }
            DiskSnapshot written = observed == null ? store.createCopy(target, content, selectedFormat, source)
                    : store.write(target, content, selectedFormat, observed);
            synchronized (this) {
                base = external = written;
                format = written.format();
                conflictLocked = false;
                state = text().equals(written.text()) ? SyncState.SYNCHRONIZED : SyncState.SYNCHRONIZING;
                message = null;
                recoveryDirty = true;
            }
            persistRecovery();
        });
    }

    /** Saves another file while retaining the original document, its path, and its unresolved conflict. */
    public CompletableFuture<Path> saveCopy(Path target) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        submitIo(() -> {
            try {
                String content;
                TextFormat selected;
                DiskSnapshot source;
                synchronized (this) { content = text(); selected = format; source = base; }
                Path destination = target.toAbsolutePath().normalize();
                store.createCopy(destination, content, selected, source);
                result.complete(destination);
            } catch (Exception exception) { result.completeExceptionally(exception); }
        }, result);
        return result.copy();
    }

    /** Assigning a path always creates a new file; existing files require an explicit overwrite decision. */
    public CompletableFuture<DocumentSnapshot> assignPath(Path target) {
        return action(() -> {
            Path destination = target.toAbsolutePath().normalize();
            String content;
            TextFormat selected;
            DiskSnapshot source;
            synchronized (this) { content = text(); selected = format; source = base; }
            DiskSnapshot written = store.createCopy(destination, content, selected, source);
            Path canonical = store.canonicalPath(destination);
            synchronized (this) {
                path = canonical;
                base = external = written;
                format = written.format();
                conflictLocked = false;
                suspension = Suspension.NONE;
                state = text().equals(written.text()) ? SyncState.SYNCHRONIZED : SyncState.SYNCHRONIZING;
                message = null;
                recoveryDirty = true;
            }
            onPathChanged.accept(this);
            persistRecovery();
        });
    }

    /** Call after flush, then await before moving/deleting files. While paused only recovery is written. */
    public CompletableFuture<DocumentSnapshot> pause() {
        return action(() -> {
            synchronized (this) {
                if (suspension == Suspension.RECOVERY_REVIEW) throw new IllegalStateException("Review the recovered pause first");
                suspension = Suspension.FILE_OPERATION;
                if (!conflictLocked && state != SyncState.ERROR) state = SyncState.PAUSED;
            }
            persistRecovery();
        });
    }

    public synchronized void resume() {
        if (closed) return;
        if (suspension == Suspension.RECOVERY_REVIEW) return; // Only explicit review may resume an uncertain recovered path.
        suspension = Suspension.NONE;
        if (!conflictLocked) state = SyncState.SYNCHRONIZING;
        schedule();
    }

    /** Review the current disk through the normal merge/conflict rules; never implies overwrite. */
    public synchronized CompletableFuture<DocumentSnapshot> reviewAndResume() {
        requireOpen();
        if (suspension != Suspension.RECOVERY_REVIEW) return flush();
        suspension = Suspension.NONE;
        if (!conflictLocked) state = SyncState.SYNCHRONIZING;
        return flush();
    }

    /** Caller owns the filesystem mutation and must pause this session before remapping. */
    public CompletableFuture<DocumentSnapshot> remapPath(Path target) {
        return action(() -> {
            Path canonical = store.canonicalPath(target);
            synchronized (this) {
                if (!isPaused()) throw new IllegalStateException("Pause the document before remapping its path");
                path = canonical;
                recoveryDirty = true;
            }
            onPathChanged.accept(this);
            persistRecovery();
        });
    }

    /** Close is a durable deferral for drafts/conflicts, never an implicit discard or overwrite. */
    public synchronized CompletableFuture<Void> close() {
        if (closed) return CompletableFuture.completedFuture(null);
        if (closeOperation != null) return closeOperation.copy();
        closing = true;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        closeOperation = completion;
        flush().thenCompose(ignored -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            submitIo(() -> {
                try {
                    persistRecovery();
                    drafts.release(id);
                    synchronized (this) { closed = true; }
                    onClose.accept(this);
                    listeners.clear();
                    result.complete(null);
                } catch (IOException exception) {
                    synchronized (this) { closing = false; }
                    result.completeExceptionally(exception);
                }
            }, result);
            return result;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                synchronized (this) { closing = false; closeOperation = null; }
                completion.completeExceptionally(failure);
            } else completion.complete(null);
        });
        return completion.copy();
    }

    public CompletableFuture<Void> discardDraft() {
        synchronized (this) {
            if (path != null) return CompletableFuture.failedFuture(new IllegalStateException("Only unnamed drafts can be discarded"));
        }
        return discardRecoveryAndClose();
    }

    /** Explicitly deleting a file also authorizes removing its recovery entry. Never used for ordinary close. */
    public CompletableFuture<Void> discardRecoveryAndClose() {
        return close().thenCompose(ignored -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            submitIo(() -> {
                try { drafts.remove(id); drafts.release(id); result.complete(null); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            }, result);
            return result;
        }).copy();
    }

    private synchronized void schedule() {
        if (scheduled || closed) return;
        scheduled = true;
        io.submit(this::synchronize, this::rejectSynchronization);
    }

    private void rejectSynchronization(RuntimeException cause) {
        List<CompletableFuture<DocumentSnapshot>> rejected;
        synchronized (this) {
            scheduled = false;
            recoveryFailure = new IOException("Document I/O executor rejected synchronization", cause);
            if (!conflictLocked) state = SyncState.ERROR;
            message = recoveryFailure.getMessage();
            rejected = List.copyOf(waiters); waiters.clear();
        }
        for (var waiter : rejected) waiter.completeExceptionally(cause);
        publish(snapshot());
    }

    private void submitIo(Runnable operation, CompletableFuture<?> result) {
        try { io.submit(operation, result::completeExceptionally); }
        catch (RuntimeException failure) { result.completeExceptionally(failure); }
    }

    private void synchronize() {
        int retries = 0;
        try {
            while (true) {
                long observedRevision;
                synchronized (this) { observedRevision = revision; }
                try { syncOnce(); }
                catch (FileChangedException exception) {
                    if (++retries < 4) continue;
                    fail(exception);
                } catch (IOException exception) { fail(exception); }
                synchronized (this) {
                    if (revision != observedRevision && path != null && !isPaused() && !conflictLocked) continue;
                    // Edits during conflict/draft persistence must also reach durable recovery before flush resolves.
                    if (recoveryDirty && recoveryFailure == null) continue;
                    break;
                }
            }
        } finally {
            List<CompletableFuture<DocumentSnapshot>> completed;
            DocumentSnapshot snapshot;
            IOException failure;
            synchronized (this) {
                scheduled = false;
                if (recoveryDirty && recoveryFailure == null && !closed) {
                    // An edit may arrive between the final loop check and acquiring this monitor.
                    // It observed scheduled=true, so this completion must enqueue its replacement.
                    completed = List.of();
                    schedule();
                } else {
                    completed = List.copyOf(waiters);
                    waiters.clear();
                }
                snapshot = snapshot();
                failure = recoveryFailure;
            }
            publish(snapshot);
            for (CompletableFuture<DocumentSnapshot> waiter : completed) {
                if (failure == null) waiter.complete(snapshot);
                else waiter.completeExceptionally(failure);
            }
        }
    }

    private void syncOnce() throws IOException {
        Path target;
        synchronized (this) {
            target = path;
            if (isPaused() || target == null) {
                if (!conflictLocked && state != SyncState.ERROR) state = isPaused() ? SyncState.PAUSED : SyncState.DRAFT;
            }
        }
        checkpointRecovery();
        synchronized (this) { if (isPaused() || target == null) return; }
        DiskSnapshot disk;
        try { disk = store.read(target); }
        catch (NoSuchFileException exception) {
            synchronized (this) {
                external = null;
                conflictLocked = true;
                state = SyncState.DELETED;
                message = "File was removed from disk. Restore it explicitly or save your text to a new path.";
                recoveryDirty = true;
            }
            checkpointRecovery();
            return;
        }
        synchronized (this) {
            // A file with no line breaks has no observable separator style; retain the explicit choice.
            if (base != null && disk.fingerprint().equals(base.fingerprint()) && !disk.text().contains("\n")) {
                disk = new DiskSnapshot(disk.text(), disk.rawText(), base.format(), disk.fingerprint());
            }
        }
        String local;
        String common;
        long localRevision;
        boolean locked;
        synchronized (this) {
            external = disk;
            local = text();
            common = base == null ? "" : base.text();
            localRevision = revision;
            locked = conflictLocked;
        }
        if (locked) { checkpointRecovery(); return; }
        var merged = TextMerge.merge(common, local, disk.text());
        if (merged.isEmpty()) {
            synchronized (this) {
                if (revision != localRevision) return;
                conflictLocked = true;
                state = SyncState.CONFLICT;
                message = "This file changed on disk and in the editor. Choose which version to keep, or compare both.";
                recoveryDirty = true;
            }
            checkpointRecovery();
            return;
        }
        if (!merged.get().equals(local)) {
            // Both model text and its Swing adapter change in one notification-executor turn.
            // A keystroke between reading the disk and that turn invalidates this merge.
            if (!applyReconciledText(localRevision, merged.get(), disk, false)) return;
        }
        String toWrite;
        TextFormat writeFormat;
        long writeRevision;
        synchronized (this) {
            if (merged.get().equals(local) && revision != localRevision) return;
            base = disk;
            format = disk.format();
            message = null;
            toWrite = text().equals(disk.text()) ? null : text();
            state = toWrite == null ? SyncState.SYNCHRONIZED : SyncState.SYNCHRONIZING;
            writeFormat = format;
            writeRevision = revision;
        }
        checkpointRecovery();
        publish(snapshot());
        if (toWrite == null) return;
        DiskSnapshot written = store.write(target, toWrite, writeFormat, disk);
        synchronized (this) {
            base = external = written;
            format = written.format();
            state = revision == writeRevision ? SyncState.SYNCHRONIZED : SyncState.SYNCHRONIZING;
            message = null;
            recoveryDirty = true;
        }
        checkpointRecovery();
    }

    /** Source-file autosave remains available even when the independent recovery store is unavailable. */
    private void checkpointRecovery() {
        try { persistRecovery(); }
        catch (IOException exception) {
            // persistRecovery retains the error independently of source synchronization/conflict state.
            // A later successful checkpoint clears it; source autosave can still proceed.
        }
    }

    private void fail(IOException exception) {
        synchronized (this) {
            if (!conflictLocked) state = SyncState.ERROR;
            message = exception.getMessage();
            recoveryDirty = true;
        }
        try { persistRecovery(); }
        catch (IOException recoveryException) {
            synchronized (this) {
                recoveryFailure = recoveryException;
            }
        }
    }

    private void persistRecovery() throws IOException {
        RecoveryDraft draft;
        boolean remove;
        synchronized (this) {
            remove = state == SyncState.SYNCHRONIZED && path != null && base != null && text().equals(base.text());
            draft = new RecoveryDraft(id, path, text(), revision, format, base, external, state, message);
        }
        try {
            if (remove) drafts.remove(id);
            else drafts.save(draft);
            synchronized (this) {
                if (revision == draft.revision()) recoveryDirty = false;
                recoveryFailure = null;
            }
        } catch (IOException exception) {
            synchronized (this) { recoveryFailure = exception; }
            throw exception;
        }
    }

    private CompletableFuture<DocumentSnapshot> action(IoAction operation) {
        CompletableFuture<DocumentSnapshot> result = new CompletableFuture<>();
        synchronized (this) {
            if (closed || closing) return CompletableFuture.failedFuture(new IllegalStateException("Document is closed"));
        }
        submitIo(() -> {
            try {
                operation.run();
                DocumentSnapshot snapshot = snapshot();
                publish(snapshot);
                result.complete(snapshot);
                synchronized (this) { if (state == SyncState.SYNCHRONIZING) schedule(); }
            } catch (Exception exception) {
                if (exception instanceof IOException failure) fail(failure);
                publish(snapshot());
                result.completeExceptionally(exception);
            }
        }, result);
        return result.copy();
    }

    private synchronized Path requirePath() {
        if (path == null) throw new IllegalStateException("The draft has no path yet");
        return path;
    }

    private synchronized void requireOpen() {
        if (closed || closing) throw new IllegalStateException("Document is closed");
    }

    private synchronized void setText(String text) {
        if (text().equals(text)) return;
        buffer.replace(0, buffer.length(), text);
        revision++;
        cachedText = text;
        cachedRevision = revision;
        recoveryDirty = true;
    }

    private void publish(DocumentSnapshot snapshot) {
        try {
            notifications.execute(() -> {
                synchronized (this) { if (closed || snapshot.revision() != revision) return; }
                notifyListeners(snapshot);
            });
        } catch (RuntimeException failure) { log("Document notification dispatch failed", failure); }
    }

    private Throwable notifyListeners(DocumentSnapshot snapshot) {
        Throwable first = null;
        for (Consumer<DocumentSnapshot> listener : listeners) try { listener.accept(snapshot); }
        catch (RuntimeException failure) {
            if (first == null) first = failure;
            else if (first != failure) first.addSuppressed(failure);
            log("Document observer failed", failure);
        }
        return first;
    }
    private static void log(String message, Throwable failure) {
        System.getLogger(DocumentSession.class.getName()).log(System.Logger.Level.WARNING, message, failure);
    }

    private boolean applyReconciledText(long expectedRevision, String text, DiskSnapshot disk, boolean resolveConflict) throws IOException {
        CompletableFuture<Boolean> applied;
        try {
            applied = CompletableFuture.supplyAsync(() -> {
                DocumentSnapshot snapshot;
                synchronized (this) {
                    if (closed || revision != expectedRevision) return false;
                    setText(text);
                    base = external = disk;
                    format = disk.format();
                    if (resolveConflict) conflictLocked = false;
                    state = text.equals(disk.text()) ? SyncState.SYNCHRONIZED : SyncState.SYNCHRONIZING;
                    message = null;
                    recoveryDirty = true;
                    snapshot = snapshot();
                }
                notifyListeners(snapshot);
                return true;
            }, notifications);
        } catch (RuntimeException failure) {
            throw new IOException("Could not dispatch the external text update", failure);
        }
        try { return applied.join(); }
        catch (java.util.concurrent.CompletionException exception) {
            throw new IOException("Could not apply the external text update", exception.getCause());
        }
    }

    @FunctionalInterface private interface IoAction { void run() throws IOException; }
}
