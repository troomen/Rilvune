package dev.rilvune.editor.application;

import java.util.concurrent.CompletableFuture;

/** A prepared close operation. The coordinator always finishes it to restore preparation locks. */
public interface TabClosePreparation {
    CompletableFuture<TabCloseResult> commit();
    void finish();
}
