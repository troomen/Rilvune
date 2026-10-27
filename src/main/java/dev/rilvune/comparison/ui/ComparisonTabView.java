package dev.rilvune.comparison.ui;

import dev.rilvune.comparison.domain.ComparisonTabModel;
import dev.rilvune.diff.application.DiffController;
import dev.rilvune.diff.domain.*;
import dev.rilvune.diff.ui.DiffView;
import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.ui.*;
import dev.rilvune.platform.tabs.ui.TabPresentation;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.session.domain.SessionState;
import dev.rilvune.session.domain.TabDescriptor;
import dev.rilvune.settings.domain.EditorSettings;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A virtual tab over two live documents. All mutations go through the destination binding. */
public final class ComparisonTabView implements TextBackedTabView {
    private final String id;
    private final TextTabController tabController;
    private final DiffController controller;
    private final Timer debounce;
    private final DocumentListener edits;
    private final Supplier<CompletableFuture<Boolean>> close;
    private final Runnable presentationChanged;
    private final Consumer<Path> reveal;
    private final Consumer<String> report;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private DocumentBindings.Lease left;
    private DocumentBindings.Lease right;
    private EditorView leftView;
    private EditorView rightView;
    private DiffView view;
    private EditorTheme theme;
    private long observedLeft = -1;
    private long observedRight = -1;
    private boolean firstResult = true;
    private boolean restored;
    private boolean closed;

    public ComparisonTabView(String id, DocumentBindings.Lease left, DocumentBindings.Lease right,
                             EditorSettings settings, EditorTheme theme, DiffEngine engine, ExecutorService workers,
                             Consumer<EditorView> saveCopy, Consumer<Path> reveal, Consumer<String> report,
                             Consumer<DocumentSnapshot> inspectConflict,
                             Supplier<CompletableFuture<Boolean>> close, Runnable presentationChanged) {
        this.id = id; this.tabController = new TextTabController(new ComparisonTabModel(id, this::state), this); this.left = left; this.right = right; this.theme = theme;
        this.close = close; this.presentationChanged = presentationChanged;
        this.reveal = reveal; this.report = report;
        controller = new DiffController(engine, workers, SwingUtilities::invokeLater, this::receive, message -> {
            view.showError(message); ready.complete(null);
        });
        debounce = new Timer(120, event -> compare()); debounce.setRepeats(false);
        leftView = new EditorView(left.binding(), settings, theme, ignored -> stateChanged(), saveCopy, report, inspectConflict);
        rightView = new EditorView(right.binding(), settings, theme, ignored -> stateChanged(), saveCopy, report, inspectConflict);
        leftView.textComponent().getAccessibleContext().setAccessibleName("Left comparison file");
        rightView.textComponent().getAccessibleContext().setAccessibleName("Right comparison file");
        view = new DiffView(source(leftView), source(rightView), theme, Optional.of(new DiffView.Transfers() {
            @Override public boolean allowed(Side destination) { return transferable(destination); }
            @Override public void copy(DiffHunk hunk, Side from) { transfer(hunk, from); }
        }), Optional.of(this::swap), ignored -> invalidate(), report);
        edits = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { invalidate(); }
            @Override public void removeUpdate(DocumentEvent event) { invalidate(); }
            @Override public void changedUpdate(DocumentEvent event) { }
        };
        left.binding().model().addDocumentListener(edits); right.binding().model().addDocumentListener(edits);
        menus(); stateChanged(); invalidate();
    }
    private DiffView.Source source(EditorView editor) { return new DiffView.Source(editor, editor.textEditor()); }
    public DiffView diffView() { return view; }
    public EditorView leftView() { return leftView; }
    public EditorView rightView() { return rightView; }
    public CompletableFuture<Void> ready() { return ready; }
    public boolean compares(UUID a, UUID b) {
        UUID x = left.binding().session().id(), y = right.binding().session().id();
        return x.equals(a) && y.equals(b) || x.equals(b) && y.equals(a);
    }
    private DocumentBindings.Lease lease(Side side) { return side == Side.LEFT ? left : right; }
    private EditorView pane(Side side) { return side == Side.LEFT ? leftView : rightView; }
    private boolean matches(DiffResult result) {
        var a = left.binding().currentSnapshot(); var b = right.binding().currentSnapshot();
        return a.isPresent() && b.isPresent() && result.left().sourceId().equals(a.orElseThrow().id().toString())
                && result.right().sourceId().equals(b.orElseThrow().id().toString())
                && result.left().revision() == a.orElseThrow().revision() && result.right().revision() == b.orElseThrow().revision();
    }
    private boolean transferable(Side destination) {
        DocumentBindings.Lease lease = lease(destination);
        DocumentSnapshot snapshot = lease.binding().session().snapshot();
        return !closed && !lease.isReleased() && lease.binding().editable() && !snapshot.needsAttention()
                && !lease.binding().session().isPaused() && view != null && view.result().map(this::matches).orElse(false);
    }
    public boolean transfer(DiffHunk hunk, Side from) {
        var current = view.result();
        if (current.isEmpty() || !current.orElseThrow().hunks().contains(hunk) || !matches(current.orElseThrow()) || !transferable(from.opposite())) {
            invalidate(); report.accept("The files changed. Review the updated comparison before applying a change."); return false;
        }
        DiffResult result = current.orElseThrow();
        TextRange source = result.range(hunk, from), destination = result.range(hunk, from.opposite());
        String text = result.source(from).text().substring(source.startOffset(), source.endOffset());
        boolean applied = lease(from.opposite()).binding().replaceIfCurrent(result.source(from.opposite()).revision(),
                destination.startOffset(), destination.length(), text);
        if (!applied) { invalidate(); report.accept("The destination changed. Review the updated comparison."); }
        return applied;
    }
    private void invalidate() {
        if (closed || view == null) return;
        controller.invalidate(); view.invalidateResult(); debounce.restart();
    }
    private void compare() {
        if (closed) return;
        if (left.binding().session().isClosed() || right.binding().session().isClosed()) {
            view.showError("A source document is closed · close or reopen this comparison"); ready.complete(null); return;
        }
        var a = left.binding().currentSnapshot(); var b = right.binding().currentSnapshot();
        if (a.isEmpty() || b.isEmpty()) { debounce.restart(); return; }
        DocumentSnapshot x = a.orElseThrow(), y = b.orElseThrow();
        observedLeft = x.revision(); observedRight = y.revision();
        controller.compare(new TextSnapshot(x.id().toString(), x.revision(), x.text()),
                new TextSnapshot(y.id().toString(), y.revision(), y.text()), view.options());
    }
    private void receive(DiffResult result) {
        if (closed) return;
        if (!matches(result)) { invalidate(); return; }
        view.showResult(result);
        if (firstResult && !restored) view.navigate(0);
        firstResult = false; ready.complete(null);
    }
    private void stateChanged() {
        if (closed || view == null) return;
        DocumentSnapshot a = left.binding().session().snapshot(), b = right.binding().session().snapshot();
        view.headers(label(left), path(a), label(right), path(b));
        if (a.revision() != observedLeft || b.revision() != observedRight) invalidate();
        else view.refreshActions();
        presentationChanged.run();
    }
    private String label(DocumentBindings.Lease source) {
        return source.binding().session().snapshot().name() + (source.binding().session().isClosed() ? " · closed" : "");
    }
    private String path(DocumentSnapshot snapshot) { return snapshot.path() == null ? "Draft · " + snapshot.id() : snapshot.path().toString(); }
    private void menus() {
        for (Side side : Side.values()) pane(side).textEditor().setMenuFactory(editor -> {
            JPopupMenu menu = TextEditor.standardMenu(editor); view.addContextActions(menu, side, editor.contextOffset());
            Path path = lease(side).binding().session().path();
            if (path != null) {
                menu.addSeparator(); JMenuItem item = new JMenuItem("Reveal source in Project"); item.addActionListener(event -> reveal.accept(path)); menu.add(item);
                JMenuItem copy = new JMenuItem("Copy source path"); copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(path.toString()), null)); menu.add(copy);
            }
            return menu;
        });
    }
    public void swap() {
        DocumentBindings.Lease lease = left; left = right; right = lease;
        EditorView pane = leftView; leftView = rightView; rightView = pane;
        leftView.textComponent().getAccessibleContext().setAccessibleName("Left comparison file");
        rightView.textComponent().getAccessibleContext().setAccessibleName("Right comparison file");
        view.setSources(source(leftView), source(rightView)); menus(); observedLeft = -1; observedRight = -1;
        stateChanged(); invalidate();
    }
    public SessionState.ComparisonTabDescriptor state() {
        var a = leftView.textEditor().captureView(); var b = rightView.textEditor().captureView();
        return new SessionState.ComparisonTabDescriptor(id, reference(left), reference(right), a.dot(), b.dot(), a.viewport().y, b.viewport().y,
                view.unifiedMode(), view.options().ignoreWhitespace());
    }
    private SessionState.Source reference(DocumentBindings.Lease source) {
        return new SessionState.Source(source.binding().session().id().toString(), source.binding().session().path());
    }
    public void restore(SessionState.ComparisonTabDescriptor state) {
        restored = true;
        view.restoreOptions(state.unified(), state.ignoreWhitespace());
        leftView.textEditor().restoreView(new TextEditor.ViewState(state.leftCaret(), state.leftCaret(), new Point(0, state.leftScroll())));
        rightView.textEditor().restoreView(new TextEditor.ViewState(state.rightCaret(), state.rightCaret(), new Point(0, state.rightScroll())));
    }
    @Override public TextTabController controller() { return tabController; }
    @Override public TabDescriptor descriptor() { return state(); }
    @Override public java.util.Set<Path> filePaths() { return documents().stream().map(lease -> lease.binding().session().path()).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()); }
    @Override public Optional<Path> activeFilePath() { return textTarget().flatMap(text -> documentViews().stream().filter(editor -> editor.textEditor() == text).map(editor -> editor.session().path()).filter(java.util.Objects::nonNull).findFirst()); }
    @Override public String id() { return id; }
    @Override public JComponent component() { return view; }
    @Override public TabPresentation presentation() {
        boolean attention = left.binding().session().snapshot().needsAttention() || right.binding().session().snapshot().needsAttention();
        return new TabPresentation("⇄ " + label(left) + " ↔ " + label(right) + (attention ? "  !" : ""),
                path(left.binding().session().snapshot()) + " ↔ " + path(right.binding().session().snapshot()),
                attention ? new Color(0xE7B66B) : theme.text());
    }
    @Override public List<DocumentBindings.Lease> documents() { return List.of(left, right); }
    @Override public List<EditorView> documentViews() { return List.of(leftView, rightView); }
    @Override public void applySettings(EditorSettings settings, EditorTheme theme) {
        this.theme = theme;
        view.withoutScrollSync(() -> {
            leftView.applySettings(settings, theme); rightView.applySettings(settings, theme); view.applyTheme(theme);
        });
    }
    @Override public void preserveViewState(Runnable update) { view.preserveViewState(update); }
    @Override public void refreshState() { stateChanged(); if (left.binding().session().isClosed() || right.binding().session().isClosed()) invalidate(); }
    @Override public void focusDocument(UUID id) { view.focus(left.binding().session().id().equals(id) ? Side.LEFT : Side.RIGHT); }
    @Override public void focusContent() { view.focusContent(); }
    @Override public Optional<TextEditor> textTarget() { return view.textTarget(); }
    @Override public CompletableFuture<Boolean> requestClose() { return close.get(); }
    @Override public void dispose() {
        if (closed) return;
        closed = true; debounce.stop(); controller.close();
        left.binding().model().removeDocumentListener(edits); right.binding().model().removeDocumentListener(edits);
        view.close(); leftView.close(); rightView.close(); ready.complete(null);
    }
}
