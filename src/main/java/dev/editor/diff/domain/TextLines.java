package dev.editor.diff.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Tokens retain their newline, including the final-newline distinction. No synthetic source lines. */
public final class TextLines {
    private final String text;
    private final List<String> lines;
    private final int[] starts;

    private TextLines(String text, List<String> lines, int[] starts) {
        this.text = text; this.lines = List.copyOf(lines); this.starts = starts;
    }
    public static TextLines parse(String text, int maximumLines, BooleanSupplier cancelled) {
        List<String> lines = new ArrayList<>(); List<Integer> starts = new ArrayList<>();
        int start = 0;
        for (int offset = 0; offset < text.length(); offset++) {
            if ((offset & 4095) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
            if (text.charAt(offset) == '\n') {
                starts.add(start); lines.add(text.substring(start, offset + 1)); start = offset + 1;
                if (lines.size() > maximumLines) throw new DiffLimitException("Too many lines to compare interactively");
            }
        }
        if (start < text.length()) { starts.add(start); lines.add(text.substring(start)); }
        if (lines.size() > maximumLines) throw new DiffLimitException("Too many lines to compare interactively");
        starts.add(text.length());
        return new TextLines(text, lines, starts.stream().mapToInt(Integer::intValue).toArray());
    }
    public int size() { return lines.size(); }
    public List<String> tokens() { return lines; }
    public int offset(int line) { return starts[Math.clamp(line, 0, lines.size())]; }
    public int lineAtOffset(int offset) {
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("Offset outside snapshot");
        int index = java.util.Arrays.binarySearch(starts, offset);
        return index >= 0 ? index : -index - 2;
    }
    public TextRange range(int start, int end) { return new TextRange(start, end, offset(start), offset(end)); }
    public String text(TextRange range) { return text.substring(range.startOffset(), range.endOffset()); }
}
