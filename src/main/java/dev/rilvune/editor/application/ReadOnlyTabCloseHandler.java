package dev.rilvune.editor.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** No writes or recovery to drain. Resource disposal follows successful workspace removal. */
public final class ReadOnlyTabCloseHandler implements TabCloseHandler {
    public static final ReadOnlyTabCloseHandler INSTANCE = new ReadOnlyTabCloseHandler();
    private ReadOnlyTabCloseHandler() { }
    @Override public CompletableFuture<TabClosePreparation> prepare(List<EditorTabController> controllers) {
        var ids = controllers.stream().map(controller -> controller.model().id()).collect(Collectors.toSet());
        return CompletableFuture.completedFuture(new TabClosePreparation() {
            public CompletableFuture<TabCloseResult> commit() { return CompletableFuture.completedFuture(new TabCloseResult(ids, null)); }
            public void finish() { }
        });
    }
}
