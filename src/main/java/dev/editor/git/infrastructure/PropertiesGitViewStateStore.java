package dev.editor.git.infrastructure;

import dev.editor.git.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Atomic profile writes; no state is written to the workspace or its Git directory. */
public final class PropertiesGitViewStateStore implements GitViewStateStore {
    private final Path file;
    public PropertiesGitViewStateStore(Path profile) { file = profile.resolve("git-view.properties"); }
    @Override public GitViewState load() throws IOException {
        if (!Files.exists(file)) return GitViewState.defaults();
        Properties values = new Properties(); try (Reader input = Files.newBufferedReader(file)) { values.load(input); }
        try {
            return new GitViewState(integer(values, "x", -1), integer(values, "y", -1), Math.clamp(integer(values, "width", 1440), 760, 10000), Math.clamp(integer(values, "height", 900), 500, 10000),
                    bool(values, "maximized", false), bool(values, "docked", false), fraction(values, "vertical", .51), fraction(values, "references", .18), fraction(values, "details", .68), fraction(values, "files", .42),
                    bool(values, "showReferences", true), bool(values, "showDetails", true), bool(values, "unified", false), bool(values, "ignoreWhitespace", false), bool(values, "history", false), bool(values, "currentBranch", false));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid Git window preferences; using defaults", invalid); }
    }
    @Override public void save(GitViewState state) throws IOException {
        Properties values = new Properties();
        values.setProperty("x", "" + state.x()); values.setProperty("y", "" + state.y()); values.setProperty("width", "" + state.width()); values.setProperty("height", "" + state.height());
        values.setProperty("maximized", "" + state.maximized()); values.setProperty("docked", "" + state.docked()); values.setProperty("vertical", "" + state.vertical()); values.setProperty("references", "" + state.references()); values.setProperty("details", "" + state.details()); values.setProperty("files", "" + state.files());
        values.setProperty("showReferences", "" + state.showReferences()); values.setProperty("showDetails", "" + state.showDetails()); values.setProperty("unified", "" + state.unified()); values.setProperty("ignoreWhitespace", "" + state.ignoreWhitespace()); values.setProperty("history", "" + state.history()); values.setProperty("currentBranch", "" + state.currentBranch());
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), "git-view-", ".tmp");
        try { try (Writer output = Files.newBufferedWriter(temporary)) { values.store(output, "Git viewer presentation · no repository state"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private int integer(Properties values, String key, int fallback) { return Integer.parseInt(values.getProperty(key, "" + fallback)); }
    private boolean bool(Properties values, String key, boolean fallback) { return Boolean.parseBoolean(values.getProperty(key, "" + fallback)); }
    private double fraction(Properties values, String key, double fallback) { double value = Double.parseDouble(values.getProperty(key, "" + fallback)); if (!Double.isFinite(value)) throw new IllegalArgumentException(); return Math.clamp(value, .1, .9); }
}
