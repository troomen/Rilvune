package dev.editor.diff.domain;

import java.util.List;

/** A transfer/navigation block with independently refined line and inline changes. */
public record DiffHunk(TextRange left, TextRange right, List<DiffLineChange> changes) {
    public DiffHunk {
        changes = List.copyOf(changes);
        if (changes.isEmpty()) throw new IllegalArgumentException("A diff block needs changes");
        int a = left.startOffset(), b = right.startOffset();
        for (DiffLineChange change : changes) {
            if (!left.contains(change.left()) || !right.contains(change.right())
                    || change.left().startOffset() < a || change.right().startOffset() < b) {
                throw new IllegalArgumentException("Line changes must be ordered and inside their block");
            }
            a = change.left().endOffset(); b = change.right().endOffset();
        }
    }
}
