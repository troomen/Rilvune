package dev.editor.document.ui;

import dev.editor.document.application.DocumentSession;
import dev.editor.editor.application.*;

import java.awt.Component;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

import javax.swing.*;

/** Existing text close algorithm, grouped across normal and comparison consumers. */
public final class TextTabCloseHandler implements TabCloseHandler {
    private final Component owner;
    private final Consumer<String> select;
    public TextTabCloseHandler(Component owner, Consumer<String> select) { this.owner = owner; this.select = select; }
    @Override public CompletableFuture<TabClosePreparation> prepare(List<EditorTabController> controllers) {
        var tabs = controllers.stream().map(TextTabController.class::cast).map(TextTabController::view).toList();
        var leases = tabs.stream().flatMap(tab -> tab.documents().stream()).filter(lease -> !lease.isReleased()).toList();
        var models = leases.stream().map(DocumentBindings.Lease::binding).distinct().filter(binding -> !binding.session().isClosed()).toList();
        models.forEach(model -> model.setEditingEnabled(false));
        var sessions = models.stream().map(DocumentBinding::session).distinct().toList();
        Runnable unlock = () -> models.stream().filter(model -> !model.session().isClosed()).forEach(model -> model.setEditingEnabled(true));
        CompletableFuture<Void> writes;
        try { writes = CompletableFuture.allOf(sessions.stream().map(DocumentSession::flush).toArray(CompletableFuture[]::new)); }
        catch (RuntimeException failure) { unlock.run(); return CompletableFuture.failedFuture(failure); }
        return writes.handleAsync((ignored, failure) -> {
                    if (failure != null) { unlock.run(); throw new CompletionException(failure); }
                    try {
                        var unresolved = sessions.stream().filter(session -> session.snapshot().needsAttention()).toList();
                        if (!unresolved.isEmpty()) {
                            Object[] options = {"Keep recovery and close", "Review", "Cancel"};
                            String message = unresolved.size() == 1 ? unresolved.getFirst().snapshot().name() + " needs a decision." : unresolved.size() + " document(s) need a decision.";
                            int answer = JOptionPane.showOptionDialog(owner, message + "\nBoth versions can be kept in recovery for later.",
                                    sessions.size() == 1 ? "Unresolved document" : "Unresolved documents", JOptionPane.DEFAULT_OPTION,
                                    JOptionPane.WARNING_MESSAGE, null, options, options[1]);
                            if (answer == 1) tabs.stream().filter(tab -> tab.documents().stream().anyMatch(lease -> lease.binding().session() == unresolved.getFirst()))
                                    .findFirst().ifPresent(tab -> { select.accept(tab.id()); tab.focusDocument(unresolved.getFirst().id()); });
                            if (answer != 0) { unlock.run(); return null; }
                        }
                        return new TabClosePreparation() {
                            @Override public CompletableFuture<TabCloseResult> commit() {
                                return CompletableFuture.allOf(leases.stream().map(DocumentBindings.Lease::release).toArray(CompletableFuture[]::new))
                                        .handleAsync((released, error) -> new TabCloseResult(tabs.stream()
                                                .filter(tab -> tab.documents().stream().allMatch(lease -> lease.isReleased() || lease.binding().session().isClosed()))
                                                .map(TextBackedTabView::id).collect(java.util.stream.Collectors.toSet()), error), SwingUtilities::invokeLater);
                            }
                            @Override public void finish() { unlock.run(); }
                        };
                    } catch (RuntimeException error) { unlock.run(); throw error; }
                }, SwingUtilities::invokeLater);
    }
}
