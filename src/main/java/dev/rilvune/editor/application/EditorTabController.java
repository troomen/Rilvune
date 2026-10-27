package dev.rilvune.editor.application;

import dev.rilvune.editor.domain.EditorTabModel;

/** Feature-owned lifecycle; the workspace never assumes a text document. */
public interface EditorTabController {
    EditorTabModel<?> model();
    TabCloseHandler closeHandler();
}
