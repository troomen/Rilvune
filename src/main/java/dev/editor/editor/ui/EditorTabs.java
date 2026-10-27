package dev.editor.editor.ui;

import dev.editor.editor.application.TabCloseCoordinator;
import dev.editor.platform.decoration.PathDecorations;
import dev.editor.platform.tabs.ui.*;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.settings.domain.EditorSettings;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

import javax.swing.*;

/** Single workspace registry and presentation host. Knows no content implementation. */
public class EditorTabs extends JPanel {
    protected final TabHost host;
    private final Map<String, EditorTabView> entries = new LinkedHashMap<>();
    private final TabCloseCoordinator closes = new TabCloseCoordinator(SwingUtilities::invokeLater, this::removeTab);
    private Consumer<Set<Path>> pathsListener = ignored -> { };
    private Set<Path> publishedPaths = Set.of();
    private Function<Path, List<Action>> pathActions = ignored -> List.of();
    private final Consumer<Path> reveal;
    protected final Consumer<String> report;
    private PathDecorations decorations = PathDecorations.empty();
    protected EditorTheme theme;
    protected EditorSettings settings;
    private Runnable activeTabListener = () -> { };
    public void setActiveTabListener(Runnable listener) { activeTabListener = Objects.requireNonNull(listener); listener.run(); }
    private BooleanSupplier lifecycleAllowed = () -> true;
    public EditorTabs(EditorTheme theme, EditorSettings settings, Consumer<String> report, Consumer<Path> reveal,
                      Runnable newDraft, Runnable openProject) {
        super(new BorderLayout()); this.theme = theme; this.settings = settings; this.report = report; this.reveal = reveal;
        JPanel links = new JPanel(); links.setLayout(new BoxLayout(links, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("A place for your next idea."); title.setForeground(theme.muted()); links.add(title);
        links.add(Box.createVerticalStrut(26)); links.add(button("New draft                         Ctrl+N", newDraft));
        links.add(button("Open project                 Ctrl+Alt+O", openProject)); links.add(Box.createVerticalStrut(18));
        links.add(new JLabel("Find file  Ctrl+Shift+N     Commands  Ctrl+Shift+P"));
        host = new TabHost(theme, links, tab -> {
            for (var view : tabs()) view.selected(view == tab);
            activeTabListener.run();
            ((EditorTabView) tab).activeFilePath().ifPresent(path -> report.accept(path.toString()));
        }, tab -> menu((EditorTabView) tab));
        add(host, BorderLayout.CENTER);
    }
    public void register(EditorTabView tab, boolean activate) {
        if (!tab.id().equals(tab.controller().model().id())) throw new IllegalArgumentException("Tab/model identity mismatch");
        var existing = entries.putIfAbsent(tab.id(), tab);
        if (existing != null && existing != tab) throw new IllegalArgumentException("Duplicate tab identity");
        try { tab.setDecorations(decorations); host.show(tab, activate); publishFilePaths(); }
        catch (RuntimeException failure) {
            if (existing == null) {
                entries.remove(tab.id(), tab);
                try { host.remove(tab.id()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                try { publishFilePaths(); activeTabListener.run(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    public List<EditorTabView> tabs() { return List.copyOf(entries.values()); }
    public Optional<EditorTabView> activeTab() { return host.active().map(EditorTabView.class::cast); }
    public String activeId() { return activeTab().map(EditorTabView::id).orElse(""); }
    public Optional<Path> activeFilePath() { return activeTab().flatMap(EditorTabView::activeFilePath); }
    public List<String> tabOrder() { return host.tabs().stream().map(WorkspaceTab::id).toList(); }
    public void restoreOrder(List<String> order) { host.reorder(order); }
    public void select(String id) { host.select(id); }
    public void next(int direction) { host.next(direction); }
    public void setWelcome(JComponent content) { host.setWelcome(content); }
    public void setPathContextActions(Function<Path, List<Action>> actions) { pathActions = Objects.requireNonNull(actions); }
    public void setLifecycleAllowed(BooleanSupplier allowed) { lifecycleAllowed = allowed; }
    public void removeTab(String id) { entries.remove(id); try { host.remove(id); } finally { publishFilePaths(); activeTabListener.run(); } }
    public void refreshTab(String id) { var tab = entries.get(id); if (tab != null) host.update(tab); publishFilePaths(); }
    public void refreshPresentations() { for (var tab : tabs()) { tab.refreshState(); host.update(tab); } publishFilePaths(); }
    public void setDecorations(PathDecorations value) {
        decorations = value; for (var tab : tabs()) { tab.setDecorations(value); host.update(tab); }
    }
    public void setFilePathsListener(Consumer<Set<Path>> listener) { pathsListener = listener; publishedPaths = filePaths(); listener.accept(publishedPaths); }
    private Set<Path> filePaths() { return tabs().stream().flatMap(tab -> tab.filePaths().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private void publishFilePaths() { var paths = filePaths(); if (!paths.equals(publishedPaths)) { publishedPaths = paths; pathsListener.accept(paths); } }
    public void applySettings(EditorSettings settings, EditorTheme theme) {
        this.settings = settings; this.theme = theme; tabs().forEach(tab -> tab.applySettings(settings, theme)); host.applyTheme(theme);
    }
    public void preserveViewState(Runnable update) {
        Runnable guarded = update;
        for (var tab : tabs()) { Runnable next = guarded; guarded = () -> tab.preserveViewState(next); }
        guarded.run();
    }
    public Optional<CompletableFuture<Boolean>> pendingClose(String id) { return closes.pending(id); }
    public CompletableFuture<Boolean> requestCloseActive() { return activeTab().map(tab -> requestClose(tab.id())).orElseGet(() -> CompletableFuture.completedFuture(true)); }
    public CompletableFuture<Boolean> requestClose(String id) {
        var pending = closes.pending(id); if (pending.isPresent()) return pending.get();
        var tab = entries.get(id); return tab == null ? CompletableFuture.completedFuture(true) : closeTabs(List.of(tab), true);
    }
    public CompletableFuture<Boolean> closeAll() { return closeTabs(tabs(), true); }
    public CompletableFuture<Boolean> closeAllForProject() { return closeTabs(tabs(), false); }
    private CompletableFuture<Boolean> closeTabs(List<EditorTabView> tabs, boolean showErrors) {
        if (!lifecycleAllowed.getAsBoolean()) return CompletableFuture.completedFuture(false);
        var operation = closes.close(tabs.stream().map(EditorTabView::controller).toList());
        return operation.handleAsync((accepted, failure) -> {
            refreshPresentations();
            if (failure != null) {
                if (!showErrors) throw new java.util.concurrent.CompletionException(failure);
                while (failure.getCause() != null) failure = failure.getCause();
                JOptionPane.showMessageDialog(this, failure.getMessage(), "Cannot close document", JOptionPane.ERROR_MESSAGE); return false;
            }
            return accepted;
        }, SwingUtilities::invokeLater);
    }
    public void disposeAfterShutdown() { for (var tab : tabs()) removeTab(tab.id()); }
    private JPopupMenu menu(EditorTabView tab) {
        JPopupMenu menu = new JPopupMenu(); item(menu, "Close", () -> requestClose(tab.id()));
        item(menu, "Close others", () -> closeTabs(tabs().stream().filter(other -> other != tab).toList(), true));
        item(menu, "Close all", this::closeAll);
        var actions = tab.contextActions();
        if (!actions.isEmpty()) { menu.addSeparator(); actions.forEach(menu::add); }
        tab.activeFilePath().ifPresent(path -> {
            menu.addSeparator(); pathActions.apply(path).forEach(menu::add);
            item(menu, "Copy path", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(path.toString()), null));
            item(menu, "Reveal in Project", () -> reveal.accept(path));
        });
        return menu;
    }
    private static void item(JPopupMenu menu, String title, Runnable run) { var item = new JMenuItem(title); item.addActionListener(event -> run.run()); menu.add(item); }
    private static JButton button(String title, Runnable run) { var button = new JButton(title); button.addActionListener(event -> run.run()); return button; }
}
