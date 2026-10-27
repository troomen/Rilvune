package dev.editor.platform.text.ui;

import java.util.Optional;

/** Implemented only by surfaces that can focus a text editor (including comparisons). */
public interface TextEditorTarget {
    Optional<TextEditor> textTarget();
}
