package dev.rilvune.diff.domain;

import java.util.List;

/** Corresponding changed lines, or unmatched whole lines on one side of a block. */
public record DiffLineChange(TextRange left, TextRange right, List<DiffFragment> fragments) {
    public DiffLineChange {
        fragments = List.copyOf(fragments);
        if (left.empty() && right.empty()) throw new IllegalArgumentException("A line change cannot be empty on both sides");
        if (left.empty() || right.empty()) {
            if (!fragments.isEmpty()) throw new IllegalArgumentException("Unmatched lines have no inline fragments");
        } else if (fragments.isEmpty()) throw new IllegalArgumentException("Corresponding changed lines need inline edits");
        int a = left.startOffset(), b = right.startOffset();
        for (DiffFragment fragment : fragments) {
            if (!left.span().contains(fragment.left()) || !right.span().contains(fragment.right())
                    || fragment.left().startOffset() < a || fragment.right().startOffset() < b) {
                throw new IllegalArgumentException("Inline edits must be ordered and inside their lines");
            }
            a = fragment.left().endOffset(); b = fragment.right().endOffset();
        }
    }
    public DiffKind kind() { return left.empty() ? DiffKind.ADDED : right.empty() ? DiffKind.REMOVED : DiffKind.CHANGED; }
    public TextRange range(Side side) { return side == Side.LEFT ? left : right; }
}
