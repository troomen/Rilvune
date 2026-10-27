package dev.editor.editor.application;

import dev.editor.editor.domain.EditorTabModel;

/** Feature-owned lifecycle; the workspace never assumes a text document. */
public interface EditorTabController {
    EditorTabModel<?> model();
    TabCloseHandler closeHandler();
}
