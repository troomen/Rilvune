package dev.rilvune.diff.domain;

/**
 * One readability-oriented inline region in corresponding source lines.
 * Ranges are exact, but may include unchanged characters rather than form a minimal edit script.
 */
public record DiffFragment(TextSpan left, TextSpan right) {
    public DiffFragment {
        if (left.empty() && right.empty()) throw new IllegalArgumentException("An inline edit cannot be empty on both sides");
    }
    public DiffKind kind() { return left.empty() ? DiffKind.ADDED : right.empty() ? DiffKind.REMOVED : DiffKind.CHANGED; }
    public TextSpan span(Side side) { return side == Side.LEFT ? left : right; }
}
