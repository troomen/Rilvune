package dev.editor.diff.domain;

import java.util.List;

public record DiffResult(TextSnapshot left, TextSnapshot right, TextLines leftLines, TextLines rightLines,
                         List<DiffHunk> hunks, DiffOptions options) {
    public DiffResult { hunks = List.copyOf(hunks); }
    public TextSnapshot source(Side side) { return side == Side.LEFT ? left : right; }
    public TextLines lines(Side side) { return side == Side.LEFT ? leftLines : rightLines; }
    public TextRange range(DiffHunk hunk, Side side) { return side == Side.LEFT ? hunk.left() : hunk.right(); }
    public int mapLine(Side from, int line) {
        int delta = 0;
        changes: for (DiffHunk hunk : hunks) for (DiffLineChange change : hunk.changes()) {
            TextRange a = change.range(from), b = change.range(from.opposite());
            if (line < a.firstLine()) break changes;
            if (line < a.endLine()) {
                int span = Math.max(1, a.endLine() - a.firstLine());
                return b.firstLine() + (line - a.firstLine()) * (b.endLine() - b.firstLine()) / span;
            }
            delta = b.endLine() - a.endLine();
        }
        return Math.clamp(line + delta, 0, lines(from.opposite()).size());
    }
}
