package dev.editor.settings.application;

import dev.editor.settings.domain.EditorSettings;
import dev.editor.settings.domain.SettingsStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Writes preferences off the UI thread, then publishes the committed value on the UI executor. */
public final class SettingsService {
    private final SettingsStore store;
    private final Executor ioExecutor;
    private final Executor uiExecutor;
    private final CopyOnWriteArrayList<Consumer<EditorSettings>> listeners = new CopyOnWriteArrayList<>();
    private volatile SettingsStore.Snapshot snapshot;
    private boolean applying;

    public SettingsService(SettingsStore store, Executor ioExecutor, Executor uiExecutor) throws IOException {
        this.store = Objects.requireNonNull(store);
        this.ioExecutor = Objects.requireNonNull(ioExecutor);
        this.uiExecutor = Objects.requireNonNull(uiExecutor);
        this.snapshot = store.load();
    }

    public EditorSettings current() { return snapshot.settings(); }

    public Optional<String> warning() { return snapshot.warning(); }

    public AutoCloseable listen(Consumer<EditorSettings> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    public synchronized CompletableFuture<EditorSettings> apply(EditorSettings settings) {
        Objects.requireNonNull(settings);
        if (applying) return CompletableFuture.failedFuture(new IllegalStateException("Settings are already being applied"));
        if (settings.equals(current()) && warning().isEmpty()) return CompletableFuture.completedFuture(settings);
        applying = true;
        String expectedRevision = snapshot.revision();
        CompletableFuture<EditorSettings> operation = CompletableFuture.supplyAsync(() -> {
            try {
                return store.save(settings, expectedRevision);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }, ioExecutor).thenApplyAsync(saved -> {
            snapshot = saved;
            for (Consumer<EditorSettings> listener : listeners) listener.accept(saved.settings());
            return saved.settings();
        }, uiExecutor);
        return operation.whenComplete((value, failure) -> {
            synchronized (this) { applying = false; }
        });
    }

    /** Explicitly accepts the preferences committed by another application instance. */
    public synchronized CompletableFuture<EditorSettings> reload() {
        if (applying) return CompletableFuture.failedFuture(new IllegalStateException("Settings are already being applied"));
        applying = true;
        return CompletableFuture.supplyAsync(() -> {
            try { return store.load(); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
        }, ioExecutor).thenApplyAsync(loaded -> {
            snapshot = loaded;
            for (Consumer<EditorSettings> listener : listeners) listener.accept(loaded.settings());
            return loaded.settings();
        }, uiExecutor).whenComplete((value, failure) -> {
            synchronized (this) { applying = false; }
        });
    }
}
