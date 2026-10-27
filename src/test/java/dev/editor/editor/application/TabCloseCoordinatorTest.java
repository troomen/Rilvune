package dev.editor.editor.application;

import dev.editor.editor.domain.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TabCloseCoordinatorTest {
    private record State() implements EditorTabState { }
    private record Model(String id) implements EditorTabModel<State> { public State state() { return new State(); } }
    private record Controller(Model model, TabCloseHandler closeHandler) implements EditorTabController { }
    @Test void cancellationPreparesButNeverRemovesReadOnlyTabs() throws Exception {
        var removed = new ArrayList<String>(); var coordinator = new TabCloseCoordinator(Runnable::run, removed::add);
        var image = new Controller(new Model("image"), ReadOnlyTabCloseHandler.INSTANCE);
        var text = new Controller(new Model("text"), controllers -> CompletableFuture.completedFuture(null));
        assertFalse(coordinator.close(List.of(image, text)).get()); assertTrue(removed.isEmpty()); assertFalse(coordinator.busy());
    }
    @Test void actualPartialResultsAreRemovedAndPreparedHandlersAreRestoredAfterFailure() {
        var removed = new HashSet<String>(); var finished = new AtomicInteger();
        TabCloseHandler text = controllers -> CompletableFuture.completedFuture(new TabClosePreparation() {
            public CompletableFuture<TabCloseResult> commit() { return CompletableFuture.completedFuture(new TabCloseResult(Set.of("released"), new java.io.IOException("retain other document"))); }
            public void finish() { finished.incrementAndGet(); }
        });
        var coordinator = new TabCloseCoordinator(Runnable::run, removed::add);
        var operation = coordinator.close(List.of(new Controller(new Model("released"), text), new Controller(new Model("retained"), text),
                new Controller(new Model("image"), ReadOnlyTabCloseHandler.INSTANCE)));
        assertThrows(CompletionException.class, operation::join);
        assertEquals(Set.of("released", "image"), removed); assertEquals(1, finished.get()); assertFalse(coordinator.busy());
    }
    @Test void sharedHandlerPreparesOnlyOnceAndPendingStateCoversAllPages() throws Exception {
        var release = new CompletableFuture<TabClosePreparation>(); var prepared = new AtomicInteger();
        TabCloseHandler handler = controllers -> { prepared.incrementAndGet(); assertEquals(2, controllers.size()); return release; };
        var coordinator = new TabCloseCoordinator(Runnable::run, ignored -> { });
        var operation = coordinator.close(List.of(new Controller(new Model("a"), handler), new Controller(new Model("b"), handler)));
        assertEquals(1, prepared.get()); assertTrue(coordinator.pending("a").isPresent()); assertTrue(coordinator.pending("b").isPresent());
        release.complete(null); assertFalse(operation.get()); assertFalse(coordinator.busy());
    }
    @Test void aHandlerCannotRemoveTabsOutsideItsPreparedGroup() {
        var removed = new ArrayList<String>();
        TabCloseHandler buggy = controllers -> CompletableFuture.completedFuture(new TabClosePreparation() {
            public CompletableFuture<TabCloseResult> commit() { return CompletableFuture.completedFuture(new TabCloseResult(Set.of("unrelated"), null)); }
            public void finish() { }
        });
        var coordinator = new TabCloseCoordinator(Runnable::run, removed::add);
        var closing = coordinator.close(List.of(new Controller(new Model("selected"), buggy)));
        assertThrows(CompletionException.class, closing::join);
        assertTrue(removed.isEmpty()); assertFalse(coordinator.busy());
    }

    @Test void aMissingCommitResultIsAnErrorRatherThanASuccessfulClose() {
        TabCloseHandler handler = controllers -> CompletableFuture.completedFuture(new TabClosePreparation() {
            public CompletableFuture<TabCloseResult> commit() { return CompletableFuture.completedFuture(null); }
            public void finish() { }
        });
        var coordinator = new TabCloseCoordinator(Runnable::run, ignored -> fail("No tab was released"));
        assertThrows(CompletionException.class, () -> coordinator.close(List.of(new Controller(new Model("tab"), handler))).join());
        assertFalse(coordinator.busy());
    }

}
