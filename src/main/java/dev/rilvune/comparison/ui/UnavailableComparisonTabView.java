package dev.rilvune.comparison.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.comparison.domain.ComparisonTabModel;
import dev.rilvune.editor.application.EditorTabController;
import dev.rilvune.editor.application.ReadOnlyTabCloseHandler;
import dev.rilvune.editor.application.TabCloseHandler;
import dev.rilvune.editor.domain.EditorTabModel;
import dev.rilvune.editor.ui.EditorTabView;
import dev.rilvune.platform.tabs.ui.TabPresentation;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.session.domain.SessionState;
import dev.rilvune.session.domain.TabDescriptor;
import dev.rilvune.settings.domain.EditorSettings;

import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.swing.*;

/** Keeps a restorable descriptor when a source is missing, without creating or replacing files. */
final class UnavailableComparisonTabView implements EditorTabView {
    private final SessionState.ComparisonTabDescriptor state;
    private final JPanel panel = new JPanel(new GridBagLayout());
    private final JLabel message = new JLabel();
    private final Supplier<CompletableFuture<Boolean>> close;
    private EditorTheme theme;
    private String explanation;
    private final EditorTabController tabController;
    UnavailableComparisonTabView(SessionState.ComparisonTabDescriptor state, String failure, EditorTheme theme, Runnable retry,
                             Supplier<CompletableFuture<Boolean>> close) {
        this.state = state; this.theme = theme; this.close = close; this.explanation = failure;
        var model = new ComparisonTabModel(state.id(), () -> this.state, () -> explanation);
        tabController = new EditorTabController() {
            public EditorTabModel<?> model() { return model; }
            public TabCloseHandler closeHandler() { return ReadOnlyTabCloseHandler.INSTANCE; }
        };
        JPanel content = new JPanel(); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new JLabel("Comparison source unavailable")); content.add(Box.createVerticalStrut(16));
        content.add(new JLabel(name(state.left()))); content.add(new JLabel(name(state.right())));
        content.add(Box.createVerticalStrut(12)); message.setText(failure); content.add(message);
        JButton button = new JButton("Retry"); button.addActionListener(event -> retry.run()); content.add(Box.createVerticalStrut(16)); content.add(button);
        panel.add(content);
        UiTypography.apply(panel, theme);
    }
    void failure(String value) { explanation = value; message.setText(value); }
    private String name(SessionState.Source source) { return source.path() == null ? "Draft · " + source.id() : source.path().toString(); }
    @Override public EditorTabController controller() { return tabController; }
    @Override public TabDescriptor descriptor() { return state; }
    @Override public void preserveViewState(Runnable update) { update.run(); }
    @Override public String id() { return state.id(); }
    @Override public JComponent component() { return panel; }
    @Override public TabPresentation presentation() { return new TabPresentation("⇄ Unavailable comparison", name(state.left()) + " ↔ " + name(state.right()), theme.muted()); }
    @Override public void applySettings(EditorSettings settings, EditorTheme theme) { this.theme = theme; UiTypography.apply(panel, theme); }
    @Override public void refreshState() { }
    @Override public void focusContent() { panel.requestFocusInWindow(); }
    @Override public CompletableFuture<Boolean> requestClose() { return close.get(); }
    @Override public void dispose() { }
}
