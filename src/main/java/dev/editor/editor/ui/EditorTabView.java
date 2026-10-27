package dev.editor.editor.ui;

import dev.editor.editor.application.EditorTabController;
import dev.editor.platform.decoration.PathDecorations;
import dev.editor.platform.tabs.ui.WorkspaceTab;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.session.domain.TabDescriptor;
import dev.editor.settings.domain.EditorSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.swing.Action;

/** Workspace presentation; optional file/command behavior is feature-owned, never fake text. */
public interface EditorTabView extends WorkspaceTab {
    EditorTabController controller();
    TabDescriptor descriptor();
    void applySettings(EditorSettings settings, EditorTheme theme);
    void preserveViewState(Runnable update);
    void refreshState();
    default void setDecorations(PathDecorations value) { }
    default Set<Path> filePaths() { return Set.of(); }
    default Optional<Path> activeFilePath() { return Optional.empty(); }
    default List<Action> contextActions() { return List.of(); }
    default void selected(boolean active) { }
}
