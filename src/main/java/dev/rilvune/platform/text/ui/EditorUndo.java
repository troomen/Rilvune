package dev.rilvune.platform.text.ui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;

/** Typing is grouped; explicit editor commands are one undo step. Saving never touches history. */
public final class EditorUndo implements UndoableEditListener, TextEditCommands {
    private final UndoManager history = new UndoManager();
    private CompoundEdit group;
    private long lastEdit;
    private int nextOffset = -1;
    private DocumentEvent.EventType lastType;
    private boolean atomic;
    private final java.util.function.BooleanSupplier enabled;

    public EditorUndo(java.util.function.BooleanSupplier enabled) {
        this.enabled = java.util.Objects.requireNonNull(enabled, "enabled");
        history.setLimit(1000);
    }

    @Override public void undoableEditHappened(UndoableEditEvent event) {
        long now = System.nanoTime();
        DocumentEvent change = event.getEdit() instanceof DocumentEvent value ? value : null;
        boolean adjacent = change != null && change.getType() == lastType
                && (change.getOffset() == nextOffset || change.getOffset() + change.getLength() == nextOffset);
        if (group == null || (!atomic && (now - lastEdit > 700_000_000L || !adjacent))) {
            finish();
            group = new CompoundEdit();
            history.addEdit(group);
        }
        group.addEdit(event.getEdit());
        lastEdit = now;
        if (change != null) {
            lastType = change.getType();
            nextOffset = change.getOffset() + (change.getType() == DocumentEvent.EventType.INSERT ? change.getLength() : 0);
        }
    }

    public void atomic(Runnable action) {
        if (enabled()) recordUpdate(action);
    }
    /** Model reconciliation is recorded even while user commands are suspended. */
    public void recordUpdate(Runnable action) {
        if (atomic) { action.run(); return; }
        finish();
        atomic = true;
        try { action.run(); } finally { atomic = false; finish(); }
    }
    @Override public boolean enabled() { return enabled.getAsBoolean(); }
    public void undo() { if (!enabled.getAsBoolean()) return; finish(); if (history.canUndo()) history.undo(); }
    public void redo() { if (!enabled.getAsBoolean()) return; finish(); if (history.canRedo()) history.redo(); }
    public boolean canUndo() { finish(); return history.canUndo(); }
    public void finish() { if (group != null) { group.end(); group = null; } }
}
