package dev.rilvune.git.infrastructure;

import dev.rilvune.git.domain.*;
import dev.rilvune.git.domain.FileHistoryQuery.Lines;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** NUL-framed metadata and filenames; selection patches supply Git's historical line coordinates. */
final class FileHistoryParser {
    private static final Pattern ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private FileHistoryParser() { }

    static List<FileHistoryEntry> files(byte[] bytes) throws IOException {
        List<String> fields = GitProtocol.fields(bytes);
        List<FileHistoryEntry> entries = new ArrayList<>();
        int cursor = 0;
        try {
            while (cursor < fields.size()) {
                while (cursor < fields.size() && fields.get(cursor).isBlank()) cursor++;
                if (cursor == fields.size()) break;
                GitCommitHeader commit = header(fields, cursor); cursor += 5;
                List<GitChange> changes = new ArrayList<>();
                while (cursor < fields.size()) {
                    String code = fields.get(cursor).stripLeading();
                    if (ID.matcher(code).matches()) break;
                    cursor++;
                    if (code.isEmpty()) continue;
                    if (!code.matches("[AMDTRC][0-9]*")) throw new IOException("Malformed file history status");
                    String old = path(fields.get(cursor++));
                    String current = code.startsWith("R") || code.startsWith("C") ? path(fields.get(cursor++)) : old;
                    changes.add(new GitChange(current, old, code.startsWith("R") ? "Renamed" : code.startsWith("D") ? "Deleted"
                            : code.startsWith("A") ? "Added" : "Modified", '.', '.', false));
                }
                if (changes.size() != 1) throw new IOException("File history returned an ambiguous file change");
                entries.add(new FileHistoryEntry(commit, changes.getFirst(), null, null));
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException failure) {
            throw new IOException("Malformed file history", failure);
        }
        return List.copyOf(entries);
    }

    @FunctionalInterface interface Changes { List<GitChange> read(GitCommitHeader commit) throws IOException; }

    static FileHistoryPage selection(byte[] bytes, Changes changes, int offset, int maximum) throws IOException {
        List<String> fields = GitProtocol.fields(bytes);
        List<FileHistoryEntry> entries = new ArrayList<>();
        int rows = 0;
        try {
            if (!fields.getFirst().isEmpty()) throw new IOException("Malformed line history framing");
            for (int cursor = 1; cursor < fields.size(); cursor += 6, rows++) {
                GitCommitHeader commit = header(fields, cursor);
                if (rows < offset || entries.size() == maximum) continue;
                if (commit.parents().size() > 1) throw new IOException("Selected lines were resolved in a merge; use file history to inspect its parents");
                String patch = fields.get(cursor + 5).stripLeading();
                GitChange matching = null;
                int headerLength = 0;
                // Git -L writes patch paths without quoting, even when names contain newlines.
                // Match the complete header against NUL-framed tree changes; never split a filename into lines.
                for (GitChange change : changes.read(commit)) {
                    String before = commit.parents().isEmpty() || change.status().equals("Added") ? "/dev/null" : "a/" + change.oldPath();
                    String after = change.status().equals("Deleted") ? "/dev/null" : "b/" + change.path();
                    String expected = "diff --git a/" + change.oldPath() + " b/" + change.path()
                            + "\n--- " + before + "\n+++ " + after + "\n";
                    if (patch.startsWith(expected)) {
                        if (matching != null) throw new IOException("Ambiguous line history path; use file history");
                        matching = change; headerLength = expected.length();
                    }
                }
                if (matching == null) throw new IOException("Git cannot describe this selection change unambiguously; use file history");
                Lines before = null, after = null;
                for (String line : patch.substring(headerLength).split("\n")) {
                    if (line.startsWith("diff --git ")) throw new IOException("Selection has multiple merge-parent patches; use file history");
                    var match = HUNK.matcher(line);
                    if (match.matches()) {
                        before = extend(before, range(match.group(1), match.group(2)));
                        after = extend(after, range(match.group(3), match.group(4)));
                    }
                }
                if (before == null || after == null) throw new IOException("Missing line history patch");
                entries.add(new FileHistoryEntry(commit, matching, before, after));
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException failure) {
            throw new IOException("Malformed line history", failure);
        }
        return new FileHistoryPage(entries, rows > offset + entries.size());
    }
    private static GitCommitHeader header(List<String> fields, int cursor) throws IOException {
        String id = fields.get(cursor), parents = fields.get(cursor + 1);
        if (!ID.matcher(id).matches()) throw new IOException("Invalid history commit ID");
        List<String> parentIds = parents.isEmpty() ? List.of() : List.of(parents.split(" "));
        for (String parent : parentIds) if (!ID.matcher(parent).matches()) throw new IOException("Invalid history parent ID");
        return new GitCommitHeader(id, parentIds, fields.get(cursor + 2), fields.get(cursor + 3), fields.get(cursor + 4));
    }
    private static String path(String value) throws IOException { RepositoryPaths.resolve(Path.of("/"), value); return value; }
    private static Lines range(String start, String count) {
        int size = count == null ? 1 : Integer.parseInt(count);
        int first = Integer.parseInt(start) - (size == 0 ? 0 : 1);
        return new Lines(first, Math.addExact(first, size));
    }
    private static Lines extend(Lines prior, Lines next) {
        return prior == null ? next : new Lines(Math.min(prior.first(), next.first()), Math.max(prior.end(), next.end()));
    }
}
