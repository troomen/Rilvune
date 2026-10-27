package dev.editor.comparison.ui;

import dev.editor.comparison.application.*;
import dev.editor.diff.domain.DiffEngine;
import dev.editor.document.application.DocumentSession;
import dev.editor.document.domain.DocumentSnapshot;
import dev.editor.document.ui.*;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.session.domain.SessionState;
import dev.editor.settings.domain.EditorSettings;

import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.SwingUtilities;

/** UI composition and project-scoped acquisition. Neither diff rendering nor file persistence lives here. */
public final class ComparisonTabs {
    private final FileComparisonService service;
    private final DocumentBindings bindings;
    private final TextTabs tabs;
    private final DiffEngine engine;
    private final ExecutorService workers;
    private final Supplier<EditorSettings> settings;
    private final Supplier<EditorTheme> theme;
    private final Consumer<EditorView> saveCopy;
    private final Consumer<Path> reveal;
    private final Consumer<String> report;
    private final Consumer<DocumentSnapshot> inspectConflict;
    private final java.util.Set<CompletableFuture<?>> pending = new java.util.HashSet<>();
    private final java.util.Set<DocumentBindings.Lease> unusedLeases = new java.util.HashSet<>();
    private long generation;
    private dev.editor.platform.text.ui.syntax.SyntaxSupport syntax = dev.editor.platform.text.ui.syntax.SyntaxSupport.none();

    public void syntaxSupport(dev.editor.platform.text.ui.syntax.SyntaxSupport support) {
        syntax = java.util.Objects.requireNonNull(support);
        liveTabs().forEach(tab -> { tab.leftView().installSyntax(support); tab.rightView().installSyntax(support); });
    }

    public ComparisonTabs(FileComparisonService service, DocumentBindings bindings, TextTabs tabs,
                           DiffEngine engine, ExecutorService workers, Supplier<EditorSettings> settings, Supplier<EditorTheme> theme,
                           Consumer<EditorView> saveCopy, Consumer<Path> reveal, Consumer<String> report,
                           Consumer<DocumentSnapshot> inspectConflict) {
        this.service = service; this.bindings = bindings; this.tabs = tabs;
        this.engine = engine; this.workers = workers; this.settings = settings; this.theme = theme;
        this.saveCopy = saveCopy; this.reveal = reveal; this.report = report;
        this.inspectConflict = inspectConflict;
    }
    /** Capture the active live file when its context menu opens, before opening a virtual tab. */
    public Action compareWithCurrentAction(SessionState.Source source) {
        long request = generation;
        Optional<DocumentSession> active = tabs.activeDocument();
        boolean same = active.filter(session -> source.id().equals(session.id().toString())
                || source.path() != null && source.path().toAbsolutePath().normalize().equals(session.path())).isPresent();
        Action action = new AbstractAction("Compare File With Editor") {
            @Override public void actionPerformed(ActionEvent event) {
                if (!isEnabled() || request != generation) return;
                DocumentSession target = active.orElseThrow();
                if (target.isClosed() || target.isClosing() || tabs.pendingClose(target.id()).isPresent()) return;
                open(source, new SessionState.Source(target.id().toString(), target.path())).whenCompleteAsync((tab, failure) -> {
                    if (failure != null && generation == request && !(root(failure) instanceof CancellationException)) report.accept(message(failure));
                }, SwingUtilities::invokeLater);
            }
        };
        action.setEnabled(active.isPresent() && !same);
        action.putValue(Action.SHORT_DESCRIPTION, active.isEmpty() ? "Open a file in the editor to compare with"
                : same ? "This file is already active" : "Compare with " + active.orElseThrow().snapshot().name()
                + (active.orElseThrow().path() == null ? " · draft" : " · " + active.orElseThrow().path()));
        return action;
    }
    public CompletableFuture<ComparisonTabView> open(SessionState.Source left, SessionState.Source right) {
        return open(left, right, "comparison:" + UUID.randomUUID(), null);
    }
    public CompletableFuture<Void> restore(SessionState.ComparisonTabDescriptor state) {
        long request = generation;
        return open(state.left(), state.right(), state.id(), state).handleAsync((tab, failure) -> {
            if (failure != null && generation == request && !(root(failure) instanceof CancellationException)) unavailable(state, message(failure));
            return null;
        }, SwingUtilities::invokeLater);
    }
    private CompletableFuture<ComparisonTabView> open(SessionState.Source left, SessionState.Source right, String id, SessionState.ComparisonTabDescriptor restore) {
        long request = generation;
        CompletableFuture<ComparisonTabView> operation = service.open(left, right).thenComposeAsync(pair -> {
            if (generation != request) return service.discard(pair).thenCompose(ignored -> CompletableFuture.failedFuture(new CancellationException()));
            Optional<ComparisonTabView> existing = liveTabs().stream().filter(tab -> tab.compares(pair.left().session().id(), pair.right().session().id())).findFirst();
            if (existing.isPresent()) return service.discard(pair).thenApplyAsync(ignored -> {
                if (generation != request) throw new CancellationException();
                if (restore == null) tabs.select(existing.orElseThrow().id());
                return existing.orElseThrow();
            }, SwingUtilities::invokeLater);
            DocumentBindings.Lease first = null;
            DocumentBindings.Lease second = null;
            ComparisonTabView tab = null;
            try {
                first = bindings.adopt(pair.left());
                second = bindings.adopt(pair.right());
                tab = new ComparisonTabView(id, first, second, settings.get(), theme.get(), engine, workers, saveCopy, reveal, report, inspectConflict,
                        () -> tabs.requestClose(id), () -> tabs.refreshTab(id));
                tab.leftView().installSyntax(syntax); tab.rightView().installSyntax(syntax);
            } catch (RuntimeException failure) {
                if (tab != null) try { tab.dispose(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                if (first == null) service.releaseLater(pair.left()); else releaseUnused(first);
                if (second == null) service.releaseLater(pair.right()); else releaseUnused(second);
                return CompletableFuture.failedFuture(failure);
            }
            tabs.tabs().stream().filter(candidate -> candidate.id().equals(id)).findFirst().ifPresent(candidate -> tabs.removeTab(candidate.id()));
            tabs.register(tab, restore == null);
            if (restore != null) tab.restore(restore);
            return CompletableFuture.completedFuture(tab);
        }, SwingUtilities::invokeLater);
        pending.add(operation);
        operation.whenCompleteAsync((tab, failure) -> pending.remove(operation), SwingUtilities::invokeLater);
        return operation;
    }
    private void unavailable(SessionState.ComparisonTabDescriptor state, String failure) {
        var existing = tabs.tabs().stream().filter(tab -> tab.id().equals(state.id())).findFirst();
        if (existing.isPresent() && existing.orElseThrow() instanceof UnavailableComparisonTabView unavailable) { unavailable.failure(failure); return; }
        tabs.register(new UnavailableComparisonTabView(state, failure, theme.get(), () -> restore(state), () -> tabs.requestClose(state.id())), false);
    }
    public List<ComparisonTabView> liveTabs() { return tabs.tabs().stream().filter(ComparisonTabView.class::isInstance).map(ComparisonTabView.class::cast).toList(); }
    public CompletableFuture<Void> quiesce() {
        generation++;
        return CompletableFuture.allOf(pending.stream().map(future -> future.handle((ignored, failure) -> null)).toArray(CompletableFuture[]::new))
                .thenComposeAsync(ignored -> CompletableFuture.allOf(unusedLeases.stream().map(this::releaseUnused).toArray(CompletableFuture[]::new)), SwingUtilities::invokeLater)
                .thenCompose(ignored -> service.cleanup());
    }
    private CompletableFuture<Void> releaseUnused(DocumentBindings.Lease lease) {
        unusedLeases.add(lease);
        return lease.release().thenRunAsync(() -> unusedLeases.remove(lease), SwingUtilities::invokeLater);
    }
    private Throwable root(Throwable failure) { while (failure.getCause() != null && failure instanceof CompletionException) failure = failure.getCause(); return failure; }
    private String message(Throwable failure) { Throwable value = root(failure); return value.getMessage() == null ? "Cannot compare these files" : value.getMessage(); }
}
