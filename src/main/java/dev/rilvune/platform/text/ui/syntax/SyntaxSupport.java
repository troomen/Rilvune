package dev.rilvune.platform.text.ui.syntax;

import dev.rilvune.platform.text.domain.syntax.*;
import javax.swing.text.Document;

/** Narrow capability passed to text hosts. Backend construction belongs to app composition. */
public interface SyntaxSupport {
    SyntaxHandle acquire(Document document, SyntaxSource source, Runnable changed);

    static SyntaxSupport none() {
        return new SyntaxSupport() {
            public SyntaxHandle acquire(Document document, SyntaxSource source, Runnable changed) {
                return unavailable("Syntax highlighting is unavailable for this source");
            }
        };
    }
    static SyntaxHandle unavailable(String reason) {
        return new SyntaxHandle() {
            public SyntaxResult result() { return null; }
            public SyntaxStatus status() { return SyntaxStatus.of(SyntaxStatus.State.PLAIN, reason); }
            public SyntaxLanguageId language() { return SyntaxLanguageId.PLAIN; }
            public SyntaxSelection selection() { return SyntaxSelection.AUTO; }
            public void source(SyntaxSource source) { }
            public void replaceSource(SyntaxSource source) { }
            public void selection(SyntaxSelection selection) { }
            public void enabled(boolean enabled) { }
            public void activity(boolean visible, boolean focused) { }
            public void retry() { }
            public void close() { }
        };
    }
}
