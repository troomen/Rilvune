package dev.rilvune.git.application;

import dev.rilvune.diff.domain.*;
import java.util.Arrays;

/** Exact unchanged-line mapping. Modified lines never inherit a nearby author's attribution. */
public final class EditorAttribution {
    private EditorAttribution() { }
    public static dev.rilvune.git.domain.FileHistoryQuery.Lines committedRange(DiffResult diff,
            dev.rilvune.git.domain.FileHistoryQuery.Lines selection) {
        int[] mapping = unchangedLines(diff);
        if (selection.first() == selection.end() || selection.end() > mapping.length)
            throw new IllegalArgumentException("Selected lines have no committed history");
        int first = mapping[selection.first()];
        for (int line = selection.first(); line < selection.end(); line++) {
            if (first < 0 || mapping[line] != first + line - selection.first())
                throw new IllegalArgumentException("Selected lines contain local changes; use file history to inspect committed versions");
        }
        return new dev.rilvune.git.domain.FileHistoryQuery.Lines(first, first + selection.end() - selection.first());
    }
    public static int[] unchangedLines(DiffResult diff) {
        int[] mapping = new int[diff.rightLines().size()]; Arrays.fill(mapping, -1);
        int a = 0, b = 0;
        for (DiffHunk hunk : diff.hunks()) for (DiffLineChange change : hunk.changes()) {
            while (a < change.left().firstLine() && b < change.right().firstLine()) {
                if (diff.leftLines().tokens().get(a).equals(diff.rightLines().tokens().get(b))) mapping[b] = a;
                a++; b++;
            }
            a = change.left().endLine(); b = change.right().endLine();
        }
        while (a < diff.leftLines().size() && b < mapping.length) {
            if (diff.leftLines().tokens().get(a).equals(diff.rightLines().tokens().get(b))) mapping[b] = a;
            a++; b++;
        }
        return mapping;
    }
}
