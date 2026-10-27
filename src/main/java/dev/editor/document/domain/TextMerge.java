package dev.editor.document.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Conservative three-way merge. Ambiguity is a conflict, never an invitation to discard text. */
public final class TextMerge {
    private static final long MAX_DIFF_CELLS = 2_000_000;

    private TextMerge() { }

    public record Change(int start, int end, String replacement) { }

    public static Optional<String> merge(String base, String local, String external) {
        if (local.equals(external)) return Optional.of(local);
        if (base.equals(local)) return Optional.of(external);
        if (base.equals(external)) return Optional.of(local);
        List<Change> localChanges = changes(base, local);
        List<Change> externalChanges = changes(base, external);
        List<Change> combined = new ArrayList<>(localChanges);
        for (Change externalChange : externalChanges) {
            boolean duplicate = false;
            for (Change localChange : localChanges) {
                if (localChange.equals(externalChange)) {
                    duplicate = true;
                    break;
                }
                boolean bothRanges = localChange.start() < localChange.end()
                        && externalChange.start() < externalChange.end();
                boolean overlaps = bothRanges
                        ? localChange.start() < externalChange.end() && externalChange.start() < localChange.end()
                        : localChange.start() <= externalChange.end() && externalChange.start() <= localChange.end();
                if (overlaps) return Optional.empty();
            }
            if (!duplicate) combined.add(externalChange);
        }
        combined.sort(Comparator.comparingInt(Change::start).reversed());
        StringBuilder result = new StringBuilder(base);
        for (Change change : combined) result.replace(change.start(), change.end(), change.replacement());
        return Optional.of(result.toString());
    }

    /** Line-level LCS, then character trimming; bounded to avoid pathological quadratic work. */
    public static List<Change> changes(String before, String after) {
        if (before.equals(after)) return List.of();
        // Avoid allocating millions of tiny line objects for unusually dense text.
        if (before.chars().filter(c -> c == '\n').limit(50_001).count() > 50_000
                || after.chars().filter(c -> c == '\n').limit(50_001).count() > 50_000) {
            return List.of(trim(before, after, 0));
        }
        List<String> a = lines(before);
        List<String> b = lines(after);
        if ((long) (a.size() + 1) * (b.size() + 1) > MAX_DIFF_CELLS) {
            return List.of(trim(before, after, 0));
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? 1 + lcs[i + 1][j + 1]
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Change> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        int offset = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                offset += a.get(i++).length();
                j++;
                continue;
            }
            int start = offset;
            StringBuilder removed = new StringBuilder();
            StringBuilder inserted = new StringBuilder();
            while (i < a.size() || j < b.size()) {
                if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) break;
                if (i < a.size() && (j == b.size() || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    String line = a.get(i++);
                    removed.append(line);
                    offset += line.length();
                } else {
                    inserted.append(b.get(j++));
                }
            }
            result.add(trim(removed.toString(), inserted.toString(), start));
        }
        return List.copyOf(result);
    }

    private static Change trim(String before, String after, int offset) {
        int prefix = 0;
        while (prefix < before.length() && prefix < after.length()
                && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < before.length() - prefix && suffix < after.length() - prefix
                && before.charAt(before.length() - suffix - 1) == after.charAt(after.length() - suffix - 1)) suffix++;
        return new Change(offset + prefix, offset + before.length() - suffix,
                after.substring(prefix, after.length() - suffix));
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }
}
