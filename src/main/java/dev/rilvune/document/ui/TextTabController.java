package dev.rilvune.document.ui;

import dev.rilvune.editor.application.*;
import dev.rilvune.editor.domain.EditorTabModel;

/** Adapter into the workspace lifecycle; all durable content remains in DocumentSession. */
public final class TextTabController implements EditorTabController {
    private final EditorTabModel<?> model;
    private final TextBackedTabView view;
    private TextTabCloseHandler handler;
    public TextTabController(EditorTabModel<?> model, TextBackedTabView view) { this.model = model; this.view = view; }
    public void attach(TextTabCloseHandler value) {
        if (view.documents().isEmpty()) throw new IllegalStateException("A text consumer must own actual document leases");
        if (handler != null && handler != value) throw new IllegalStateException("Text tab already belongs to a workspace");
        handler = value;
    }
    @Override public EditorTabModel<?> model() { return model; }
    @Override public TabCloseHandler closeHandler() { return java.util.Objects.requireNonNull(handler, "Register text tab before closing"); }
    TextBackedTabView view() { return view; }
}
