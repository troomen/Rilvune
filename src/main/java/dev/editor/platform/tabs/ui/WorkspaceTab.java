package dev.editor.platform.tabs.ui;

import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;

/** A UI surface with its own identity and lifecycle. File policy belongs to its owner. */
public interface WorkspaceTab {
    String id();
    JComponent component();
    TabPresentation presentation();
    void focusContent();
    /** The owner performs its close policy and removes the tab after success. */
    CompletableFuture<Boolean> requestClose();
    /** Releases view resources after removal; never implicitly saves or discards a file. */
    void dispose();
}
