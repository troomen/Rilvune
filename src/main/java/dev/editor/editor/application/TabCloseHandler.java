package dev.editor.editor.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Instances group related consumers into one preparation/decision (e.g. shared documents). */
public interface TabCloseHandler {
    /** A null preparation denotes a canceled close decision; no tab has been removed yet. */
    CompletableFuture<TabClosePreparation> prepare(List<EditorTabController> controllers);
}
