package dev.rilvune.diff.ui;

import dev.rilvune.diff.domain.*;

import java.util.ArrayList;
import java.util.List;

/** A bounded read-only projection. It is never parsed back into edits. */
final class UnifiedText {
    record Change(int start, int end, DiffKind kind, boolean wholeLine) { }
    record Projection(String text, List<Integer> hunkOffsets, List<Change> changes) {
        Projection { hunkOffsets = List.copyOf(hunkOffsets); changes = List.copyOf(changes); }
    }
    private UnifiedText() { }
    static Projection render(DiffResult diff) {
        if ((long) diff.left().text().length() + diff.right().text().length() > 2 * 1024 * 1024) {
            return new Projection("Unified preview is too large. Use the side-by-side view.\n", List.of(), List.of());
        }
        StringBuilder text = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        int[][] positions = {new int[diff.leftLines().size()], new int[diff.rightLines().size()]};
        int[][] ends = {new int[diff.leftLines().size()], new int[diff.rightLines().size()]};
        int left = 0;
        for (DiffHunk hunk : diff.hunks()) {
            while (left < hunk.left().firstLine()) append(text, ' ', diff.leftLines().tokens().get(left++));
            offsets.add(text.length());
            appendVersion(text, diff.leftLines(), hunk.left(), '-', positions[0], ends[0]);
            appendVersion(text, diff.rightLines(), hunk.right(), '+', positions[1], ends[1]);
            for (DiffLineChange change : hunk.changes()) for (Side side : Side.values()) {
                if (change.kind() != DiffKind.CHANGED) project(changes, diff.lines(side), change.range(side).span(), change.kind(), true, positions[side.ordinal()], ends[side.ordinal()]);
                else for (DiffFragment fragment : change.fragments()) project(changes, diff.lines(side), fragment.span(side), fragment.kind(), false, positions[side.ordinal()], ends[side.ordinal()]);
            }
            left = hunk.left().endLine();
        }
        while (left < diff.leftLines().size()) append(text, ' ', diff.leftLines().tokens().get(left++));
        changes.sort(java.util.Comparator.comparingInt(Change::start).thenComparingInt(Change::end));
        return new Projection(text.toString(), offsets, changes);
    }
    private static void appendVersion(StringBuilder result, TextLines lines, TextRange range, char prefix, int[] positions, int[] ends) {
        for (int line = range.firstLine(); line < range.endLine(); line++) {
            positions[line] = result.length() + 1; append(result, prefix, lines.tokens().get(line)); ends[line] = result.length();
        }
    }
    private static void project(List<Change> result, TextLines lines, TextSpan span, DiffKind kind, boolean wholeLine, int[] positions, int[] ends) {
        if (span.empty()) return;
        int first = lines.lineAtOffset(span.startOffset()), last = lines.lineAtOffset(span.endOffset() - 1);
        for (int line = first; line <= last; line++) {
            int start = wholeLine ? positions[line] - 1 : positions[line] + Math.max(0, span.startOffset() - lines.offset(line));
            int end = wholeLine ? ends[line] : positions[line] + Math.min(lines.tokens().get(line).length(), span.endOffset() - lines.offset(line));
            result.add(new Change(start, end, kind, wholeLine));
        }
    }
    private static void append(StringBuilder result, char prefix, String line) {
        result.append(prefix).append(line);
        if (!line.endsWith("\n")) result.append("\n\\ No newline at end of file\n");
    }
}
