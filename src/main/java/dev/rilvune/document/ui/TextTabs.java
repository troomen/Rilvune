package dev.rilvune.document.ui;

import dev.rilvune.document.application.DocumentHandle;
import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.domain.TextTabModel;
import dev.rilvune.editor.ui.EditorTabView;
import dev.rilvune.editor.ui.EditorTabs;
import dev.rilvune.platform.decoration.PathDecoration;
import dev.rilvune.platform.decoration.PathDecorations;
import dev.rilvune.platform.tabs.ui.TabPresentation;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorTarget;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FileStatusColors;
import dev.rilvune.session.domain.SessionState;
import dev.rilvune.session.domain.TabDescriptor;
import dev.rilvune.settings.domain.EditorSettings;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.*;

/** Text feature facade over the content-independent workspace registry. */
public final class TextTabs extends EditorTabs {
    private final DocumentBindings bindings;
    private final Consumer<EditorView> copy;
    private final Consumer<DocumentSnapshot> inspectConflict;
    private final Function<DocumentSession, Action> comparisonActions;
    private final TextTabCloseHandler textCloses;
    private final Set<DocumentBindings.Lease> unusedLeases = new HashSet<>();
    private PathDecorations decorations = PathDecorations.empty();
    private Consumer<EditorView> viewEnhancer = ignored -> { };
    public TextTabs(DocumentBindings bindings, EditorTheme theme, EditorSettings settings, Consumer<String> report,
                          Consumer<EditorView> copy, Consumer<Path> reveal, Runnable newDraft, Runnable openProject,
                          Function<DocumentSession, Action> comparisonActions, Consumer<DocumentSnapshot> inspectConflict) {
        super(theme, settings, report, reveal, newDraft, openProject);
        this.bindings = bindings; this.copy = copy; this.comparisonActions = comparisonActions; this.inspectConflict = inspectConflict;
        textCloses = new TextTabCloseHandler(this, this::select);
    }
    private EditorTabView tab(String id) { return tabs().stream().filter(tab -> tab.id().equals(id)).findFirst().orElse(null); }
    public void setViewEnhancer(Consumer<EditorView> enhancer) {
        viewEnhancer = Objects.requireNonNull(enhancer);
        views().forEach(viewEnhancer); views().forEach(view -> view.active(active().orElse(null) == view));
    }
    public EditorView show(DocumentSession session) { return show(session, true); }
    public EditorView show(DocumentSession session, boolean activate) {
        var existing = tab(session.id().toString());
        if (existing instanceof TextTabView file) { host.show(file, activate); return file.view; }
        DocumentBindings.Lease lease = bindings.acquire(session);
        EditorView view = null;
        try {
            view = new EditorView(lease.binding(), settings, theme, ignored -> refreshPresentations(), copy, report, inspectConflict);
            viewEnhancer.accept(view);
            register(new TextTabView(view, lease), activate); return view;
        } catch (RuntimeException failure) {
            try { removeTab(session.id().toString()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (view != null) try { view.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            releaseUnused(lease).exceptionally(cleanup -> { report.accept("Cannot release failed text view: " + cleanup.getMessage()); return null; });
            throw failure;
        }
    }
    /** Returns true only when this tab has accepted ownership of the supplied consumer. */
    public boolean adopt(DocumentHandle handle, boolean activate) {
        var existing = tab(handle.session().id().toString());
        if (existing instanceof TextTabView file) { host.show(file, activate); return false; }
        DocumentBindings.Lease lease = bindings.adopt(handle);
        EditorView view = null;
        try {
            view = new EditorView(lease.binding(), settings, theme, ignored -> refreshPresentations(), copy, report, inspectConflict);
            viewEnhancer.accept(view);
            register(new TextTabView(view, lease), activate);
            return true;
        } catch (RuntimeException failure) {
            try { removeTab(handle.session().id().toString()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (view != null) try { view.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            try { lease.returnOwnership(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }


    /** Retries cleanup of failed presentations before leaving the current project. */
    public CompletableFuture<Void> quiesce() {
        return CompletableFuture.allOf(List.copyOf(unusedLeases).stream().map(this::releaseUnused).toArray(CompletableFuture[]::new));
    }
    private CompletableFuture<Void> releaseUnused(DocumentBindings.Lease lease) {
        unusedLeases.add(lease);
        return lease.release().thenRunAsync(() -> unusedLeases.remove(lease), SwingUtilities::invokeLater);
    }

    @Override public void register(EditorTabView tab, boolean activate) {
        if (tab instanceof TextBackedTabView text) text.controller().attach(textCloses);
        super.register(tab, activate);
    }
    public Optional<EditorView> active() { return activeTab().filter(TextTabView.class::isInstance).map(TextTabView.class::cast).map(tab -> tab.view); }
    public Optional<TextEditor> activeTextEditor() { return activeTab().filter(TextEditorTarget.class::isInstance).map(TextEditorTarget.class::cast).flatMap(TextEditorTarget::textTarget); }
    public Optional<DocumentSession> activeDocument() {
        return activeTextEditor().flatMap(text -> allViews().stream().filter(view -> view.textEditor() == text).map(EditorView::session).findFirst())
                .filter(session -> !session.isClosed() && !session.isClosing() && pendingClose(session.id()).isEmpty());
    }
    public List<EditorView> views() { return tabs().stream().filter(TextTabView.class::isInstance).map(TextTabView.class::cast).map(tab -> tab.view).toList(); }
    public List<EditorView> allViews() { return tabs().stream().filter(TextBackedTabView.class::isInstance).map(TextBackedTabView.class::cast).flatMap(tab -> tab.documentViews().stream()).toList(); }
    public void select(UUID id) { select(id.toString()); }
    public Optional<CompletableFuture<Boolean>> pendingClose(UUID id) {
        return tabs().stream().filter(TextBackedTabView.class::isInstance).map(TextBackedTabView.class::cast)
                .filter(tab -> tab.documents().stream().anyMatch(lease -> lease.binding().session().id().equals(id)))
                .map(tab -> pendingClose(tab.id())).flatMap(Optional::stream).findFirst();
    }
    public CompletableFuture<Boolean> requestClose(EditorView view) { return requestClose(view.session().id().toString()); }
    public void remove(EditorView view) {
        var tab = tabs().stream().filter(TextTabView.class::isInstance).map(TextTabView.class::cast).filter(value -> value.view == view).findFirst();
        if (tab.isEmpty()) return;
        var value = tab.get();
        if (!value.lease.isReleased()) {
            if (!view.session().isClosed()) throw new IllegalStateException("Release document ownership before removing its tab");
            value.lease.release();
        }
        removeTab(value.id()); refreshPresentations();
    }
    @Override public void disposeAfterShutdown() {
        if (allViews().stream().anyMatch(view -> !view.session().isClosed())) throw new IllegalStateException("Close sessions before disposing tabs");
        super.disposeAfterShutdown();
    }
    private final class TextTabView implements TextBackedTabView {
        private final EditorView view;
        private final DocumentBindings.Lease lease;
        private final TextTabController controller;
        private TextTabView(EditorView view, DocumentBindings.Lease lease) {
            this.view = view; this.lease = lease;
            controller = new TextTabController(new TextTabModel(id(), view.session()::snapshot), this);
        }
        @Override public TextTabController controller() { return controller; }
        @Override public TabDescriptor descriptor() { return new SessionState.TextTabDescriptor(id(), view.session().path(), view.textComponent().getCaretPosition()); }
        @Override public java.util.Set<Path> filePaths() { return activeFilePath().map(java.util.Set::of).orElseGet(java.util.Set::of); }
        @Override public Optional<Path> activeFilePath() { return Optional.ofNullable(view.session().path()); }
        @Override public void selected(boolean active) { view.active(active); if (active && view.session().path() == null) report.accept("Drafts are kept automatically"); }
        @Override public List<Action> contextActions() {
            return List.of(comparisonActions.apply(view.session()));
        }
        @Override public void setDecorations(PathDecorations value) { decorations = value; }
        @Override public String id() { return view.session().id().toString(); }
        @Override public JComponent component() { return view; }
        @Override public TabPresentation presentation() {
            DocumentSnapshot snapshot = view.session().snapshot();
            boolean projectFile = snapshot.path() != null && decorations.root() != null && snapshot.path().startsWith(decorations.root());
            PathDecoration decoration = projectFile ? decorations.paths().get(snapshot.path()) : null;
            String status = !projectFile ? "" : decoration == null ? decorations.availability() : decoration.description();
            String tooltip = (snapshot.path() == null ? "Untitled · " + snapshot.id() : snapshot.path().toString())
                    + " · " + EditorView.stateLabel(snapshot) + (status.isEmpty() ? "" : " · " + status);
            // Synchronization warnings remain explicit; Git colors describe persisted filesystem state.
            Color normal = snapshot.needsAttention() ? new Color(0xE7B66B) : decoration == null ? theme.text() : FileStatusColors.color(decoration.tone(), false);
            Color selected = snapshot.needsAttention() ? normal : decoration == null ? theme.text() : FileStatusColors.color(decoration.tone(), true);
            return new TabPresentation(snapshot.name() + (snapshot.needsAttention() ? "  !" : ""), tooltip, normal, selected);
        }
        @Override public List<DocumentBindings.Lease> documents() { return List.of(lease); }
        @Override public List<EditorView> documentViews() { return List.of(view); }
        @Override public void applySettings(EditorSettings settings, EditorTheme theme) { view.applySettings(settings, theme); }
        @Override public void refreshState() { }
        @Override public void focusDocument(UUID id) { view.focusText(); }
        @Override public void focusContent() { view.focusText(); }
        @Override public Optional<TextEditor> textTarget() { return Optional.of(view.textEditor()); }
        @Override public CompletableFuture<Boolean> requestClose() { return TextTabs.this.requestClose(id()); }
        @Override public void dispose() { view.close(); }
    }
}
