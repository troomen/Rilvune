package dev.rilvune.settings.application;

import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.settings.domain.SettingsStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest {
    @Test
    void applyCommitsBeforeUiPublicationAndCompletesAfterUiListeners() throws Exception {
        MemoryStore store = new MemoryStore();
        QueuedExecutor io = new QueuedExecutor();
        QueuedExecutor ui = new QueuedExecutor();
        SettingsService service = new SettingsService(store, io, ui);
        List<EditorSettings> events = new ArrayList<>();
        service.listen(events::add);
        EditorSettings changed = EditorSettings.defaults().withFonts("Hack", 22, "Hack", 25);

        var operation = service.apply(changed);
        assertEquals(EditorSettings.defaults(), service.current());
        assertEquals(EditorSettings.defaults(), store.value.settings());
        assertFalse(operation.isDone());

        io.runNext();
        assertEquals(changed, store.value.settings());
        assertEquals(EditorSettings.defaults(), service.current());
        assertTrue(events.isEmpty());

        ui.runNext();
        assertEquals(changed, service.current());
        assertEquals(List.of(changed), events);
        assertEquals(changed, operation.join());
    }

    @Test
    void failedPersistenceDoesNotPublishAndCanBeRetried() throws Exception {
        MemoryStore store = new MemoryStore();
        QueuedExecutor io = new QueuedExecutor();
        QueuedExecutor ui = new QueuedExecutor();
        SettingsService service = new SettingsService(store, io, ui);
        List<EditorSettings> events = new ArrayList<>();
        service.listen(events::add);
        EditorSettings changed = EditorSettings.defaults().withFonts("Hack", 18, "Hack", 19);
        store.failWrites = true;

        var rejected = service.apply(changed);
        io.runNext();

        assertThrows(CompletionException.class, rejected::join);
        assertEquals(EditorSettings.defaults(), service.current());
        assertTrue(events.isEmpty());
        store.failWrites = false;
        var retried = service.apply(changed);
        io.runNext();
        ui.runNext();
        assertEquals(changed, retried.join());
    }

    @Test
    void duplicateApplyCannotRaceAndClosedSubscriptionReceivesNoUpdates() throws Exception {
        QueuedExecutor io = new QueuedExecutor();
        QueuedExecutor ui = new QueuedExecutor();
        SettingsService service = new SettingsService(new MemoryStore(), io, ui);
        List<EditorSettings> events = new ArrayList<>();
        AutoCloseable subscription = service.listen(events::add);
        EditorSettings changed = EditorSettings.defaults().withFonts("Hack", 18, "Hack", 19);

        var first = service.apply(changed);
        assertThrows(CompletionException.class, () -> service.apply(changed).join());
        subscription.close();
        io.runNext();
        ui.runNext();

        assertEquals(changed, first.join());
        assertTrue(events.isEmpty());
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runNext() { tasks.remove().run(); }
    }

    private static final class MemoryStore implements SettingsStore {
        private Snapshot value = new Snapshot(EditorSettings.defaults(), "0", Optional.empty());
        private boolean failWrites;
        @Override public Snapshot load() { return value; }
        @Override public Snapshot save(EditorSettings settings, String revision) throws IOException {
            if (failWrites) throw new IOException("Read-only profile");
            if (!revision.equals(value.revision())) throw new IOException("Stale revision");
            value = new Snapshot(settings, Integer.toString(Integer.parseInt(revision) + 1), Optional.empty());
            return value;
        }
    }
}
