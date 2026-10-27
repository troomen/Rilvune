package dev.editor.search.application;

import dev.editor.platform.text.domain.LiteralText;
import dev.editor.workspace.domain.Workspace;
import dev.editor.workspace.domain.WorkspaceEntry;
import dev.editor.workspace.domain.WorkspaceFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded, cancellable search over real files; independent of the tree's expanded state. */
public final class ProjectSearch {
    private final WorkspaceFiles files;
    private final int maximumResults;
    private final Set<String> excludedDirectories;
    public ProjectSearch(WorkspaceFiles files, int maximumResults, Set<String> excludedDirectories) {
        this.files = files; this.maximumResults = maximumResults; this.excludedDirectories = Set.copyOf(excludedDirectories);
    }
    public record Hit(Path path, int line, String excerpt, int offset) {
        public Hit(Path path, int line, String excerpt) { this(path, line, excerpt, 0); }
    }
    public record Result(List<Hit> hits, int skipped, boolean limited) { public Result { hits = List.copyOf(hits); } }
    public String readPreview(Workspace workspace, Path path) throws IOException { String text = normalize(files.read(workspace, path).text()); return text.startsWith("\uFEFF") ? text.substring(1) : text; }
    public Result search(Workspace workspace, String query, boolean fileNames, boolean matchCase, BooleanSupplier cancelled) throws IOException {
        if (query.isEmpty()) return new Result(List.of(), 0, false);
        var needle = new LiteralText.Query(query, matchCase, false);
        List<Hit> hits = new ArrayList<>(); Deque<Path> pending = new ArrayDeque<>(); pending.add(workspace.root()); int skipped = 0;
        try {
            while (!pending.isEmpty() && !cancelled.getAsBoolean()) {
                List<WorkspaceEntry> entries;
                try { entries = files.list(workspace, pending.removeFirst()); } catch (IOException exception) { skipped++; continue; }
                for (WorkspaceEntry entry : entries) {
                    if (cancelled.getAsBoolean()) break;
                    if (Files.isSymbolicLink(entry.path())) continue;
                    if (entry.directory()) { if (!excludedDirectories.contains(entry.name())) pending.addLast(entry.path()); }
                    else if (fileNames) {
                        String name = workspace.root().relativize(entry.path()).toString();
                        if (!LiteralText.matches(name, needle, 1, cancelled).isEmpty()) hits.add(new Hit(entry.path(), 0, name));
                    } else try {
                        String text = readPreview(workspace, entry.path());
                        List<Integer> offsets = LiteralText.matches(text, needle, maximumResults - hits.size(), cancelled);
                        int line = 1; int scanned = 0;
                        for (int offset : offsets) {
                            while (scanned < offset) if (text.charAt(scanned++) == '\n') line++;
                            int start = text.lastIndexOf('\n', offset - 1) + 1;
                            int end = text.indexOf('\n', offset); if (end < 0) end = text.length();
                            String excerpt = text.substring(start, Math.min(end, start + 180));
                            hits.add(new Hit(entry.path(), line, excerpt, offset));
                        }
                    } catch (IOException exception) { skipped++; }
                    if (hits.size() >= maximumResults) return new Result(hits, skipped, true);
                }
            }
        } catch (CancellationException ignored) {
            // Matching may stop inside a file; retain results from completed files.
        }
        return new Result(hits, skipped, false);
    }
    private static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }
}
