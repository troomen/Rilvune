package dev.editor.git.ui.editor;

import dev.editor.git.application.*;
import dev.editor.git.infrastructure.*;
import dev.editor.platform.text.ui.*;
import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorGitControllerTest {
    @TempDir Path temporary;
    @Test void repeatedUnchangedNotificationsCannotStarveTheDebounceOrInvalidateCurrentMarkers() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "base\n"); fixture.commit("root");
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor(); ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        var backend = new CliGitRepository(GitConfiguration.defaults());
        var statuses = new ProjectGitStatusService(backend, (location, changed) -> () -> { }, workers, scheduler, SwingUtilities::invokeLater, ProjectGitStatusService.Configuration.defaults(), error -> { });
        var service = new EditorGitService(backend, statuses, workers, SwingUtilities::invokeLater, EditorGitService.Configuration.defaults(), error -> { });
        class Source implements LiveEditorDocument {
            Snapshot value = new Snapshot("document", fixture.root.resolve("a"), 0, "changed\n", true, false, "LF"); Runnable listener = () -> { };
            public Optional<Snapshot> snapshot() { return Optional.of(value); }
            public AutoCloseable observe(Runnable changed) { listener = changed; return () -> listener = () -> { }; }
            public boolean replace(long revision, int offset, String expected, String replacement) { return false; }
        }
        Source source = new Source(); EditorGitController[] controller = new EditorGitController[1]; TextEditor[] editor = new TextEditor[1]; javax.swing.Timer[] pulse = new javax.swing.Timer[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                statuses.project(fixture.root);
                editor[0] = new TextEditor(new ReadOnlyTextModel("changed\n").document(), Optional.empty(), new TextEditorSettings(4, true, false, true, false, true), EditorTheme.graphite(), ignored -> { }, TextEditor::standardMenu);
                controller[0] = new EditorGitController(service, source, editor[0], new dev.editor.diff.infrastructure.MyersDiffEngine(dev.editor.diff.infrastructure.DiffLimits.interactive()), workers, EditorTheme.graphite(), Clock.systemUTC(), ZoneId.of("UTC"), Locale.ENGLISH, ignored -> { }, ignored -> { });
                controller[0].active(true);
                pulse[0] = new javax.swing.Timer(10, event -> source.listener.run()); pulse[0].start();
            });
            await(() -> !editor[0].gutter().presentation().markers().isEmpty());
            SwingUtilities.invokeAndWait(() -> { source.value = new LiveEditorDocument.Snapshot("document", source.value.path(), 1, "new\n", true, false, "LF"); source.listener.run(); });
            await(() -> !editor[0].gutter().presentation().markers().isEmpty() && editor[0].gutter().presentation().markers().getFirst().endOffset() == 4);
            assertTrue(pulse[0].isRunning());
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (pulse[0] != null) pulse[0].stop(); if (controller[0] != null) controller[0].close(); if (editor[0] != null) editor[0].close(); service.close(); statuses.close(); });
            scheduler.shutdownNow(); workers.shutdownNow();
        }
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) { boolean[] ready = {false}; SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean()); if (ready[0]) return; Thread.sleep(10); }
        fail("Repeated notifications starved editor comparison");
    }
}
