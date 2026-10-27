package dev.rilvune.platform.text.ui;

import java.util.*;

/** View-only geometry. Source offsets/text never contain padding or fold markers. */
public record TextLineLayout(Map<Integer, Integer> padding, List<Fold> folds) {
    public record Fold(int firstLine, int endLine) { public Fold { if (firstLine < 0 || endLine <= firstLine) throw new IllegalArgumentException(); } }
    public TextLineLayout { padding = Map.copyOf(padding); folds = List.copyOf(folds); }
    public static TextLineLayout empty() { return new TextLineLayout(Map.of(), List.of()); }
    public boolean decorated() { return !padding.isEmpty() || !folds.isEmpty(); }
    public Optional<Fold> fold(int line) { int low = 0, high = folds.size() - 1;
        while (low <= high) { int mid = (low + high) >>> 1; Fold fold = folds.get(mid); if (line < fold.firstLine()) high = mid - 1; else if (line >= fold.endLine()) low = mid + 1; else return Optional.of(fold); }
        return Optional.empty(); }
}
