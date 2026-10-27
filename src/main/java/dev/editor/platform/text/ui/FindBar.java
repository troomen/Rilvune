package dev.editor.platform.text.ui;

import dev.editor.platform.text.domain.LiteralText;
import dev.editor.platform.theme.TextHighlightColors;
import javax.swing.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Literal, Unicode-aware text search; replacing all matches is one undo command. */
final class FindBar extends JPanel implements AutoCloseable {
    private static final DefaultHighlighter.HighlightPainter MATCH_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(TextHighlightColors.SEARCH);
    private final JTextArea editor;
    private final Optional<TextEditCommands> editing;
    private final BooleanSupplier editable;
    private final DocumentListener textListener;
    private volatile boolean closed;
    private volatile long version;
    private boolean queued, running;
    private Operation pending;
    private final Executor worker;
    private final Consumer<String> report;
    private enum Operation { SCAN, NEXT, PREVIOUS, ONE, ALL }
    private record Result(LiteralText.Summary summary, int position, String text) { }
    private final JTextField query = new JTextField(18);
    private final JTextField replacement = new JTextField(18);
    private final JCheckBox matchCase = new JCheckBox("Aa");
    private final JCheckBox wholeWord = new JCheckBox("Words");
    private final JLabel count = new JLabel();
    private final JPanel replaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final List<Object> highlights = new ArrayList<>();

    FindBar(JTextArea editor, Optional<TextEditCommands> editing, BooleanSupplier editable,
            Executor worker, Consumer<String> report) {
        super(new GridLayout(0, 1));
        this.editor = editor;
        this.worker = worker;
        this.report = report;
        this.editing = editing;
        this.editable = editable;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row.add(new JLabel("Find")); row.add(query); row.add(matchCase); row.add(wholeWord);
        row.add(button("↑", () -> next(false))); row.add(button("↓", () -> next(true))); row.add(count);
        row.add(button("×", this::hideBar));
        replaceRow.add(new JLabel("With")); replaceRow.add(replacement);
        replaceRow.add(button("Replace", this::replaceOne)); replaceRow.add(button("Replace all", this::replaceAll));
        add(row); add(replaceRow); setVisible(false);
        query.getDocument().addDocumentListener(listener(this::refresh));
        textListener = listener(this::refresh);
        editor.getDocument().addDocumentListener(textListener);
        matchCase.addActionListener(event -> refresh()); wholeWord.addActionListener(event -> refresh());
        query.addActionListener(event -> next(true));
        registerKeyboardAction(event -> hideBar(), KeyStroke.getKeyStroke("ESCAPE"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    public void showBar(boolean replace) {
        String selected = editor.getSelectedText();
        if (selected != null && !selected.contains("\n")) query.setText(selected);
        replaceRow.setVisible(replace && editable.getAsBoolean()); setVisible(true); refresh(); query.requestFocusInWindow(); query.selectAll();
    }
    public void hideBar() { version++; pending = null; setVisible(false); clearHighlights(); editor.requestFocusInWindow(); }

    public void next(boolean forward) { request(forward ? Operation.NEXT : Operation.PREVIOUS); }
    public void replaceOne() { if (editable.getAsBoolean()) request(Operation.ONE); }
    public void replaceAll() { if (editable.getAsBoolean()) request(Operation.ALL); }
    private void refresh() { request(Operation.SCAN); }

    private void request(Operation operation) {
        version++;
        if (closed || !isVisible()) return;
        pending = operation;
        if (!running && !queued) {
            queued = true;
            SwingUtilities.invokeLater(this::launch);
        }
    }

    /** One calculation in flight and one latest request; text is copied only when work starts. */
    private void launch() {
        queued = false;
        if (running || pending == null || closed || !isVisible()) return;
        Operation operation = pending; pending = null;
        long request = version;
        String source = editor.getText();
        var needle = new LiteralText.Query(query.getText(), matchCase.isSelected(), wholeWord.isSelected());
        String inserted = replacement.getText();
        int start = editor.getSelectionStart(), end = editor.getSelectionEnd();
        running = true;
        CompletableFuture<Result> calculation;
        try {
            calculation = CompletableFuture.supplyAsync(() -> {
                java.util.function.BooleanSupplier cancelled = () -> closed || request != version;
                if (cancelled.getAsBoolean()) throw new CancellationException();
                return switch (operation) {
                    case SCAN -> new Result(LiteralText.scan(source, needle, 2000, cancelled), -1, null);
                    case NEXT, PREVIOUS -> new Result(null, LiteralText.find(source, needle,
                            operation == Operation.NEXT ? end : start, operation == Operation.NEXT, cancelled), null);
                    case ALL -> {
                        var replacement = LiteralText.replace(source, needle, inserted,
                                Integer.MAX_VALUE, LiteralText.MAX_RESULT_CHARACTERS, cancelled);
                        yield replacement.matches() == 0 ? new Result(new LiteralText.Summary(0, List.of()), -1, null)
                                : new Result(null, -1, replacement.text());
                    }
                    case ONE -> {
                        int offset = end - start == needle.text().length() && needle.at(source, start) ? start
                                : LiteralText.find(source, needle, end, true, cancelled);
                        yield new Result(null, offset, offset < 0 ? null : LiteralText.replaceAt(source, needle, inserted,
                                offset, LiteralText.MAX_RESULT_CHARACTERS));
                    }
                };
            }, worker);
        } catch (RuntimeException failure) { calculation = CompletableFuture.failedFuture(failure); }
        calculation.whenCompleteAsync((result, failure) -> {
            running = false;
            boolean selectionCurrent = operation == Operation.SCAN || operation == Operation.ALL
                    || editor.getSelectionStart() == start && editor.getSelectionEnd() == end;
            if (!closed && isVisible() && request == version && selectionCurrent) {
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                    if (!(cause instanceof CancellationException)) {
                        count.setText("Search unavailable"); report.accept(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    }
                } else if (result.summary() != null) {
                    clearHighlights();
                    count.setText(result.summary().count() + " matches");
                    for (int offset : result.summary().positions()) try {
                        highlights.add(editor.getHighlighter().addHighlight(offset, offset + needle.text().length(),
                                MATCH_PAINTER));
                    } catch (BadLocationException stale) { break; }
                } else if (result.text() != null) {
                    if (editable.getAsBoolean() && editing.orElseThrow().replaceText(editor.getDocument(), source, result.text())) {
                        if (operation == Operation.ONE) {
                            editor.setCaretPosition(Math.min(result.text().length(), result.position() + inserted.length()));
                            next(true);
                        } else refresh();
                    } else refresh();
                } else if (result.position() >= 0) editor.select(result.position(), result.position() + needle.text().length());
            }
            if (pending != null && !closed && isVisible()) launch();
        }, SwingUtilities::invokeLater);
    }
    private void clearHighlights() { for (Object highlight : highlights) editor.getHighlighter().removeHighlight(highlight); highlights.clear(); }
    @Override public void close() {
        closed = true; version++; pending = null;
        editor.getDocument().removeDocumentListener(textListener);
        clearHighlights();
    }
    private JButton button(String label, Runnable action) { JButton button = new JButton(label); button.addActionListener(event -> action.run()); return button; }
    private static DocumentListener listener(Runnable callback) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { callback.run(); }
            public void removeUpdate(DocumentEvent event) { callback.run(); }
            public void changedUpdate(DocumentEvent event) { callback.run(); }
        };
    }
}
