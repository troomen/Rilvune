package dev.editor.session.infrastructure;

import dev.editor.session.domain.SessionState;
import dev.editor.session.domain.SessionStore;
import dev.editor.session.domain.TabDescriptor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Blocking persistence adapter. Each application instance owns its session file. */
public final class SessionRepository implements SessionStore {
    private final Path directory;
    private final String instanceId;

    public SessionRepository(Path profile) throws IOException {
        this.directory = profile.resolve("sessions");
        this.instanceId = UUID.randomUUID().toString();
        Files.createDirectories(directory);
    }

    public Path profile() { return directory.getParent(); }

    public synchronized SessionState load(Path workspace) throws IOException {
        Path group = directory.resolve(key(workspace));
        if (!Files.isDirectory(group)) return SessionState.empty(workspace);
        List<Path> candidates;
        try (java.util.stream.Stream<Path> files = Files.list(group)) {
            candidates = files.filter(path -> path.toString().endsWith(".properties"))
                    .sorted(Comparator.comparingLong(this::modified).reversed()).toList();
        }
        IOException failure = null;
        for (Path path : candidates) {
            try {
                Properties data = read(path);
                if (!"2".equals(data.getProperty("schema"))) throw new IOException("Unsupported session schema: " + path);
                List<SessionState.TextTabDescriptor> opened = new ArrayList<>();
                int count = Math.min(500, Math.max(0, integer(data, "file.count", 0)));
                for (int index = 0; index < count; index++) {
                    String storedPath = data.getProperty("file." + index + ".path", "");
                    opened.add(new SessionState.TextTabDescriptor(data.getProperty("file." + index + ".id", ""),
                            storedPath.isEmpty() ? null : Path.of(storedPath), integer(data, "file." + index + ".caret", 0)));
                }
                List<SessionState.ComparisonTabDescriptor> comparisons = new ArrayList<>();
                int comparisonCount = Math.min(100, Math.max(0, integer(data, "comparison.count", 0)));
                for (int index = 0; index < comparisonCount; index++) {
                    String prefix = "comparison." + index + ".";
                    comparisons.add(new SessionState.ComparisonTabDescriptor(data.getProperty(prefix + "id"), source(data, prefix + "left."), source(data, prefix + "right."),
                            Math.max(0, integer(data, prefix + "left.caret", 0)), Math.max(0, integer(data, prefix + "right.caret", 0)),
                            Math.max(0, integer(data, prefix + "left.scroll", 0)), Math.max(0, integer(data, prefix + "right.scroll", 0)),
                            Boolean.parseBoolean(data.getProperty(prefix + "unified", "false")), Boolean.parseBoolean(data.getProperty(prefix + "ignoreWhitespace", "false"))));
                }
                List<TabDescriptor> tabs = new ArrayList<>(opened); tabs.addAll(comparisons);
                int imageCount = Math.min(100, Math.max(0, integer(data, "image.count", 0)));
                for (int index = 0; index < imageCount; index++) {
                    String prefix = "image." + index + ".";
                    String storedPath = data.getProperty(prefix + "path", "");
                    if (storedPath.isEmpty() || storedPath.length() > 8192) continue;
                    try { tabs.add(new SessionState.ImageTabDescriptor(data.getProperty(prefix + "id"), Path.of(storedPath))); }
                    catch (IllegalArgumentException invalid) {
                        System.getLogger(SessionRepository.class.getName()).log(System.Logger.Level.WARNING,
                                "Skipping invalid image tab descriptor in " + path, invalid);
                    }
                }
                List<String> order = new ArrayList<>();
                int tabCount = Math.min(700, Math.max(0, integer(data, "tab.count", 0)));
                for (int index = 0; index < tabCount; index++) order.add(data.getProperty("tab." + index));
                double fraction = Double.parseDouble(data.getProperty("project.fraction", "0.24"));
                if (!Double.isFinite(fraction) || fraction < 0.1 || fraction > 0.7) fraction = 0.24;
                return new SessionState(workspace, tabs, data.getProperty("active", ""),
                        integer(data, "x", -1), integer(data, "y", -1),
                        Math.max(900, integer(data, "width", 1440)), Math.max(600, integer(data, "height", 900)),
                        fraction, Boolean.parseBoolean(data.getProperty("project.visible", "true")),
                        Boolean.parseBoolean(data.getProperty("agents.visible", "false")), order);
            } catch (IOException | RuntimeException exception) {
                failure = new IOException("Cannot read session " + path + ": " + exception.getMessage(), exception);
            }
        }
        if (failure != null) throw failure;
        return SessionState.empty(workspace);
    }

    public synchronized void save(SessionState state) throws IOException {
        for (var tab : state.tabs()) if (!(tab instanceof SessionState.TextTabDescriptor || tab instanceof SessionState.ComparisonTabDescriptor || tab instanceof SessionState.ImageTabDescriptor))
            throw new IOException("Unsupported tab descriptor: " + tab.getClass().getSimpleName());
        var opened = state.files(); var comparisons = state.comparisons(); var images = state.images();
        Properties data = new Properties();
        data.setProperty("schema", "2");
        data.setProperty("workspace", state.workspace().toString());
        data.setProperty("updated", Instant.now().toString());
        data.setProperty("active", state.activeId());
        data.setProperty("x", Integer.toString(state.x()));
        data.setProperty("y", Integer.toString(state.y()));
        data.setProperty("width", Integer.toString(state.width()));
        data.setProperty("height", Integer.toString(state.height()));
        data.setProperty("project.fraction", Double.toString(state.projectFraction()));
        data.setProperty("project.visible", Boolean.toString(state.projectVisible()));
        data.setProperty("agents.visible", Boolean.toString(state.agentsVisible()));
        data.setProperty("file.count", Integer.toString(opened.size()));
        for (int index = 0; index < opened.size(); index++) {
            SessionState.TextTabDescriptor file = opened.get(index);
            data.setProperty("file." + index + ".id", file.id());
            data.setProperty("file." + index + ".path", file.path() == null ? "" : file.path().toString());
            data.setProperty("file." + index + ".caret", Integer.toString(file.caret()));
        }
        data.setProperty("comparison.count", Integer.toString(comparisons.size()));
        for (int index = 0; index < comparisons.size(); index++) {
            SessionState.ComparisonTabDescriptor comparison = comparisons.get(index);
            String prefix = "comparison." + index + ".";
            data.setProperty(prefix + "id", comparison.id());
            source(data, prefix + "left.", comparison.left()); source(data, prefix + "right.", comparison.right());
            data.setProperty(prefix + "left.caret", Integer.toString(comparison.leftCaret()));
            data.setProperty(prefix + "right.caret", Integer.toString(comparison.rightCaret()));
            data.setProperty(prefix + "left.scroll", Integer.toString(comparison.leftScroll()));
            data.setProperty(prefix + "right.scroll", Integer.toString(comparison.rightScroll()));
            data.setProperty(prefix + "unified", Boolean.toString(comparison.unified()));
            data.setProperty(prefix + "ignoreWhitespace", Boolean.toString(comparison.ignoreWhitespace()));
        }
        data.setProperty("image.count", Integer.toString(images.size()));
        for (int index = 0; index < images.size(); index++) {
            var image = images.get(index); String prefix = "image." + index + ".";
            data.setProperty(prefix + "id", image.id()); data.setProperty(prefix + "path", image.path().toString());
        }
        data.setProperty("tab.count", Integer.toString(state.tabOrder().size()));
        for (int index = 0; index < state.tabOrder().size(); index++) data.setProperty("tab." + index, state.tabOrder().get(index));
        Path target = directory.resolve(key(state.workspace())).resolve(instanceId + ".properties");
        Files.createDirectories(target.getParent());
        write(target, data);
    }

    public synchronized List<Path> recentProjects() throws IOException {
        Properties data = read(directory.resolve("recent.properties"));
        List<Path> result = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            String value = data.getProperty("project." + index);
            if (value != null) result.add(Path.of(value));
        }
        return List.copyOf(result);
    }

    public synchronized void remember(Path workspace) throws IOException {
        updateRecent(workspace, false);
    }

    public synchronized void forget(Path workspace) throws IOException {
        updateRecent(workspace, true);
    }

    private void updateRecent(Path workspace, boolean remove) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("recent.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            List<Path> entries = new ArrayList<>(recentProjects());
            entries.remove(workspace);
            if (!remove) entries.addFirst(workspace);
            Properties data = new Properties();
            data.setProperty("schema", "1");
            for (int index = 0; index < Math.min(20, entries.size()); index++) data.setProperty("project." + index, entries.get(index).toString());
            write(directory.resolve("recent.properties"), data);
        }
    }

    private Properties read(Path path) throws IOException {
        Properties data = new Properties();
        if (Files.exists(path)) try (Reader input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { data.load(input); }
        return data;
    }

    private void write(Path target, Properties data) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "session-", ".tmp");
        try {
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { data.store(output, "Editor session"); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private SessionState.Source source(Properties data, String prefix) {
        String path = data.getProperty(prefix + "path", "");
        return new SessionState.Source(data.getProperty(prefix + "id", ""), path.isEmpty() ? null : Path.of(path));
    }
    private void source(Properties data, String prefix, SessionState.Source source) {
        data.setProperty(prefix + "id", source.id()); data.setProperty(prefix + "path", source.path() == null ? "" : source.path().toString());
    }
    private int integer(Properties data, String key, int fallback) { return Integer.parseInt(data.getProperty(key, Integer.toString(fallback))); }
    private long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException exception) { return 0; }
    }
    private String key(Path path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
