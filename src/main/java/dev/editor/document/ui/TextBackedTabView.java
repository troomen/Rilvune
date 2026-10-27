package dev.editor.document.ui;

import dev.editor.editor.ui.EditorTabView;
import dev.editor.platform.text.ui.TextEditor;
import dev.editor.platform.text.ui.TextEditorTarget;

import java.util.*;

/** Actual consumers of text documents; never implemented by binary/placeholder tabs. */
public interface TextBackedTabView extends EditorTabView, TextEditorTarget {
    @Override TextTabController controller();
    List<DocumentBindings.Lease> documents();
    List<EditorView> documentViews();
    void focusDocument(UUID id);
    @Override default void preserveViewState(Runnable update) {
        Map<TextEditor, TextEditor.ViewState> positions = new LinkedHashMap<>();
        for (var view : documentViews()) positions.put(view.textEditor(), view.textEditor().captureView());
        try { update.run(); } finally { positions.forEach(TextEditor::restoreView); }
    }
}
