package dev.editor.document.ui;

import dev.editor.document.application.DocumentSession;
import dev.editor.document.domain.DocumentSnapshot;
import dev.editor.platform.text.ui.EditorUndo;
import dev.editor.platform.text.ui.TextEditCommands;
import dev.editor.platform.text.ui.TextEditor;

import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.Executor;

/** One Swing model, session bridge and Undo history, shared by all views of a live document. */
public final class DocumentBinding implements AutoCloseable {
    private final DocumentSession session;
    private final PlainDocument model = new PlainDocument();
    private final EditorUndo undo;
    private final DocumentListener edits;
    private final AutoCloseable subscription;
    private final List<Consumer<DocumentSnapshot>> listeners = new ArrayList<>();
    private final List<TextEditor> views = new ArrayList<>();
    private boolean applying;
    private boolean suspended;
    private boolean closed;
    private long modelRevision;

    private final Executor searchExecutor;
    private final TextEditCommands commands = new TextEditCommands() {
        public boolean enabled() { return editable(); }
        public void atomic(Runnable action) { undo.atomic(action); }
        public void undo() { editBatch(undo::undo); }
        public void redo() { editBatch(undo::redo); }
        public boolean replaceText(Document document, String expected, String replacement) {
            requireEdt();
            synchronized (session) {
                if (document != model || !editable() || applying || modelRevision != session.revision() || !session.text().equals(expected)) return false;
                if (expected.equals(replacement)) return true;
                Map<TextEditor, TextEditor.ViewState> positions = new LinkedHashMap<>();
                for (TextEditor view : views) positions.put(view, view.captureView());
                editBatch(() -> undo.atomic(() -> {
                    try { model.replace(0, model.getLength(), replacement, null); }
                    catch (BadLocationException failure) { throw new IllegalStateException(failure); }
                }));
                positions.forEach(TextEditor::restoreView);
                return true;
            }
        }
    };

    DocumentBinding(DocumentSession session, Executor searchExecutor) {
        requireEdt();
        this.session = session;
        this.searchExecutor = searchExecutor;
        undo = new EditorUndo(this::editable);
        DocumentSnapshot initial = session.snapshot();
        modelRevision = initial.revision();
        try { model.insertString(0, initial.text(), null); }
        catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
        model.setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass bypass, int offset, String value, AttributeSet attributes) throws BadLocationException {
                if (applying || editable()) super.insertString(bypass, offset, value, attributes);
            }
            @Override public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
                if (applying || editable()) super.remove(bypass, offset, length);
            }
            @Override public void replace(FilterBypass bypass, int offset, int length, String value, AttributeSet attributes) throws BadLocationException {
                if (applying || editable()) super.replace(bypass, offset, length, value, attributes);
            }
        });
        edits = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                if (applying) return;
                try { session.replace(event.getOffset(), 0, model.getText(event.getOffset(), event.getLength())); modelRevision = session.revision(); }
                catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
            }
            @Override public void removeUpdate(DocumentEvent event) {
                if (!applying) { session.replace(event.getOffset(), event.getLength(), ""); modelRevision = session.revision(); }
            }
            @Override public void changedUpdate(DocumentEvent event) { }
        };
        model.addDocumentListener(edits);
        model.addUndoableEditListener(undo);
        subscription = session.addListener(snapshot -> {
            if (SwingUtilities.isEventDispatchThread()) receive(snapshot);
            else SwingUtilities.invokeLater(() -> receive(snapshot));
        });
    }

    public Executor searchExecutor() { return searchExecutor; }
    public TextEditCommands commands() { return commands; }

    private void editBatch(Runnable action) {
        requireEdt();
        synchronized (session) {
            if (!editable() || applying || modelRevision != session.revision()) return;
            applying = true;
            try { action.run(); }
            finally {
                applying = false;
                try { session.replaceText(model.getText(0, model.getLength())); modelRevision = session.revision(); }
                catch (BadLocationException failure) { throw new IllegalStateException(failure); }
            }
        }
    }

    public DocumentSession session() { return session; }
    public Document model() { return model; }
    public EditorUndo undo() { return undo; }
    public java.util.Optional<DocumentSnapshot> currentSnapshot() {
        requireEdt();
        DocumentSnapshot snapshot = session.snapshot();
        return !closed && !applying && modelRevision == snapshot.revision() ? java.util.Optional.of(snapshot) : java.util.Optional.empty();
    }
    public boolean replaceIfCurrent(long expected, int offset, int length, String text) {
        requireEdt();
        if (!editable() || modelRevision != expected) return false;
        return session.editIfRevision(expected, () -> undo.atomic(() -> {
            try { model.replace(offset, length, text, null); }
            catch (BadLocationException failure) { throw new IllegalArgumentException("Stale comparison range", failure); }
        }));
    }
    public boolean editable() { return !closed && !suspended && !session.isClosed() && !session.isClosing(); }

    public void setEditingEnabled(boolean value) {
        requireEdt();
        suspended = !value;
        for (TextEditor view : views) view.setEditable(editable());
    }

    public AutoCloseable observe(Consumer<DocumentSnapshot> listener) {
        requireEdt();
        if (closed) throw new IllegalStateException("Document binding is closed");
        listeners.add(listener);
        return () -> { requireEdt(); listeners.remove(listener); };
    }

    public AutoCloseable attach(TextEditor view, Consumer<DocumentSnapshot> listener) {
        requireEdt();
        if (closed) throw new IllegalStateException("Document binding is closed");
        views.add(view);
        listeners.add(listener);
        view.setEditable(editable());
        receive(session.snapshot());
        return () -> {
            requireEdt();
            views.remove(view);
            listeners.remove(listener);
        };
    }

    private void receive(DocumentSnapshot snapshot) {
        requireEdt();
        if (closed || snapshot.revision() != session.revision()) return;
        try {
            String before = model.getText(0, model.getLength());
            String after = snapshot.text();
            if (!before.equals(after)) {
                Map<TextEditor, TextEditor.ViewState> positions = new LinkedHashMap<>();
                for (TextEditor view : views) positions.put(view, view.captureView());
                int prefix = 0;
                while (prefix < Math.min(before.length(), after.length()) && before.charAt(prefix) == after.charAt(prefix)) prefix++;
                int suffix = 0;
                while (suffix < Math.min(before.length(), after.length()) - prefix
                        && before.charAt(before.length() - suffix - 1) == after.charAt(after.length() - suffix - 1)) suffix++;
                int offset = prefix;
                int removed = before.length() - suffix - prefix;
                String inserted = after.substring(prefix, after.length() - suffix);
                applying = true;
                try {
                    undo.recordUpdate(() -> {
                        try { model.replace(offset, removed, inserted, null); }
                        catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
                    });
                } finally { applying = false; }
                positions.forEach(TextEditor::restoreView);
            }
        } catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
        modelRevision = snapshot.revision();
        for (Consumer<DocumentSnapshot> listener : List.copyOf(listeners)) listener.accept(snapshot);
    }

    @Override public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        views.forEach(view -> view.setEditable(false));
        views.clear(); listeners.clear();
        model.removeDocumentListener(edits);
        model.removeUndoableEditListener(undo);
        try { subscription.close(); }
        catch (Exception failure) { throw new IllegalStateException("Cannot detach document binding", failure); }
    }

    static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Document UI access must run on the EDT");
    }
}
