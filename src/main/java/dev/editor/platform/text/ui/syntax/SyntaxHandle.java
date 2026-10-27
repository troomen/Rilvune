package dev.editor.platform.text.ui.syntax;

import dev.editor.platform.text.domain.syntax.*;

/** EDT-confined, view-local selection/activity and a borrowed immutable projection. */
public interface SyntaxHandle extends AutoCloseable {
    SyntaxResult result();
    SyntaxStatus status();
    SyntaxLanguageId language();
    SyntaxSelection selection();
    long epoch();
    void source(SyntaxSource source);
    void replaceSource(SyntaxSource source);
    void selection(SyntaxSelection selection);
    void enabled(boolean enabled);
    void activity(boolean visible, boolean focused);
    void retry();
    @Override void close();
}
