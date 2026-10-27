package dev.rilvune.platform.text.ui;

import dev.rilvune.platform.theme.EditorTheme;

public interface TextEditorExtension extends AutoCloseable {
    void active(boolean active);
    void applyTheme(EditorTheme theme);
    @Override void close();
}
