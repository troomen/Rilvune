package dev.editor.platform.text.ui;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;
import javax.swing.SwingUtilities;

/** An in-memory snapshot with no file, session, Undo manager or persistence lifecycle. */
public final class ReadOnlyTextModel {
    private final PlainDocument document = new PlainDocument();
    private boolean replacing;

    public ReadOnlyTextModel(String text) {
        try { document.insertString(0, text, null); }
        catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
        document.setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass bypass, int offset, String value, AttributeSet attributes) throws BadLocationException {
                if (replacing) bypass.insertString(offset, value, attributes);
            }
            @Override public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
                if (replacing) bypass.remove(offset, length);
            }
            @Override public void replace(FilterBypass bypass, int offset, int length, String value, AttributeSet attributes) throws BadLocationException {
                if (replacing) bypass.replace(offset, length, value, attributes);
            }
        });
    }

    public Document document() { return document; }

    /** Only the host can replace a snapshot. UI edits remain blocked by the document filter. */
    public void replaceSnapshot(String text) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Snapshot replacement requires the EDT");
        replacing = true;
        try { document.replace(0, document.getLength(), text, null); }
        catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
        finally { replacing = false; }
    }
}
