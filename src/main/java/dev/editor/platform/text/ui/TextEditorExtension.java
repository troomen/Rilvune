package dev.editor.platform.text.ui;

import dev.editor.platform.theme.EditorTheme;

public interface TextEditorExtension extends AutoCloseable {
    void active(boolean active);
    void applyTheme(EditorTheme theme);
    @Override void close();
}
