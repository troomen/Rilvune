package dev.editor.editor.application;

import dev.editor.session.domain.TabDescriptor;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorRegistrationsTest {
    private record CustomTabDescriptor(String id) implements TabDescriptor { }
    @Test void anotherTabRestoresThroughRegistrationWithoutChangesToTheRegistry() throws Exception {
        var restorers = new EditorTabRestorers();
        restorers.register(CustomTabDescriptor.class, descriptor -> CompletableFuture.completedFuture(descriptor.id() + ":restored"));
        assertEquals("custom:restored", restorers.restore(new CustomTabDescriptor("custom")).get());
        assertThrows(IllegalArgumentException.class, () -> restorers.register(CustomTabDescriptor.class, descriptor -> CompletableFuture.completedFuture("duplicate")));
    }
    @Test void orderedRecognitionChoosesRegisteredEditorBeforeFallback() throws Exception {
        var openers = new EditorFileOpeners(); var selected = new AtomicInteger();
        openers.register(path -> path.toString().endsWith(".custom"), (path, request) -> { selected.set(request.intValue()); return CompletableFuture.completedFuture(null); });
        openers.register(path -> true, (path, request) -> { selected.set(-1); return CompletableFuture.completedFuture(null); });
        openers.select(Path.of("file.custom")).open(12).get(); assertEquals(12, selected.get());
        openers.select(Path.of("file.txt")).open(13).get(); assertEquals(-1, selected.get());
    }
}
