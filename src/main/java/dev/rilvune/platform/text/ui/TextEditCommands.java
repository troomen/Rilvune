package dev.rilvune.platform.text.ui;

/** Optional mutation capability. A read-only text view receives no implementation. */
public interface TextEditCommands {
    boolean enabled();
    void atomic(Runnable action);
    /** Hosts with live persistence override this to publish only the final text. */
    default boolean replaceText(javax.swing.text.Document model, String expected, String replacement) {
        try {
            if (!enabled() || !model.getText(0, model.getLength()).equals(expected)) return false;
            if (expected.equals(replacement)) return true;
            atomic(() -> {
                try { ((javax.swing.text.AbstractDocument) model).replace(0, model.getLength(), replacement, null); }
                catch (javax.swing.text.BadLocationException failure) { throw new IllegalStateException(failure); }
            });
            return true;
        } catch (javax.swing.text.BadLocationException failure) { throw new IllegalStateException(failure); }
    }
    void undo();
    void redo();
}
