package dev.rilvune.settings.infrastructure;

import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.settings.domain.SettingsStore;
import dev.rilvune.agents.domain.AgentSettings;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/** UTF-8 properties with explicit schema, atomic replacement and optimistic revision checking. */
public final class PropertiesSettingsStore implements SettingsStore {
    private final Path directory;
    private final Path file;

    public PropertiesSettingsStore(Path profileDirectory) {
        directory = profileDirectory.toAbsolutePath().normalize();
        file = directory.resolve("settings.properties");
    }

    public Path file() { return file; }

    @Override
    public Snapshot load() throws IOException {
        if (!Files.exists(file)) return new Snapshot(EditorSettings.defaults(), "missing", Optional.empty());
        byte[] bytes = Files.readAllBytes(file);
        String revision = digest(bytes);
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            if (!required(properties, "schema").equals("1")) throw new IllegalArgumentException("Unsupported settings schema");
            EditorSettings value = new EditorSettings(
                    required(properties, "ui.font.family"), integer(properties, "ui.font.size"),
                    required(properties, "editor.font.family"), integer(properties, "editor.font.size"),
                    integer(properties, "editor.tab.size"), bool(properties, "editor.use.spaces"),
                    bool(properties, "editor.soft.wrap"), bool(properties, "editor.line.numbers"),
                    bool(properties, "editor.current.line"),
                    properties.containsKey("editor.minimap") ? bool(properties, "editor.minimap") : EditorSettings.defaults().showMinimap(),
                    bool(properties, "files.show.hidden"),
                    required(properties, "files.line.ending"),
                    new AgentSettings(optionalBool(properties, "agents.send.on.enter", AgentSettings.defaults().sendOnEnter()),
                            optionalBool(properties, "agents.login.open.browser", AgentSettings.defaults().openLoginInBrowser()),
                            optionalBool(properties, "agents.restore.last.chat", AgentSettings.defaults().restoreLastChat()),
                            optionalInteger(properties, "agents.display.messages", AgentSettings.defaults().displayMessageLimit()),
                            optionalInteger(properties, "agents.display.characters.mib", AgentSettings.defaults().displayCharacterLimitMiB())),
                    optionalBool(properties, "editor.syntaxHighlighting", EditorSettings.defaults().syntaxHighlighting()));
            return new Snapshot(value, revision, Optional.empty());
        } catch (IllegalArgumentException failure) {
            return new Snapshot(EditorSettings.defaults(), revision, Optional.of(
                    "Cannot read " + file + ": " + failure.getMessage()
                            + ". Defaults are in use. The original file is preserved; Apply will keep a backup."));
        }
    }

    @Override
    public synchronized Snapshot save(EditorSettings settings, String expectedRevision) throws IOException {
        Files.createDirectories(directory);
        try (FileChannel lockChannel = FileChannel.open(directory.resolve("settings.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockChannel.lock()) {
            Snapshot actual = load();
            if (!actual.revision().equals(expectedRevision)) {
                throw new IOException("Settings changed in another editor instance. Close this dialog and reload settings before applying again.");
            }
            if (actual.warning().isPresent() && Files.exists(file)) {
                Files.copy(file, directory.resolve("settings.invalid-" + Instant.now().toEpochMilli() + "-"
                        + java.util.UUID.randomUUID() + ".properties"));
            }
            byte[] content = encode(settings);
            Path temporary = Files.createTempFile(directory, ".settings-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(content);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                // Settings are small and reside in one directory; no non-atomic fallback hides filesystem limits.
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new Snapshot(settings, digest(content), Optional.empty());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (OverlappingFileLockException failure) {
            throw new IOException("Another settings write is in progress. Please apply again.", failure);
        }
    }

    private static byte[] encode(EditorSettings value) {
        String text = "# Rilvune settings — UTF-8\n"
                + "schema=1\n"
                + "ui.font.family=" + escape(value.uiFontFamily()) + "\n"
                + "ui.font.size=" + value.uiFontSize() + "\n"
                + "editor.font.family=" + escape(value.editorFontFamily()) + "\n"
                + "editor.font.size=" + value.editorFontSize() + "\n"
                + "editor.tab.size=" + value.tabSize() + "\n"
                + "editor.use.spaces=" + value.useSpaces() + "\n"
                + "editor.soft.wrap=" + value.softWrap() + "\n"
                + "editor.line.numbers=" + value.showLineNumbers() + "\n"
                + "editor.current.line=" + value.highlightCurrentLine() + "\n"
                + "editor.minimap=" + value.showMinimap() + "\n"
                + "editor.syntaxHighlighting=" + value.syntaxHighlighting() + "\n"
                + "files.show.hidden=" + value.showHiddenFiles() + "\n"
                + "files.line.ending=" + value.defaultLineEnding() + "\n"
                + "agents.send.on.enter=" + value.agents().sendOnEnter() + "\n"
                + "agents.login.open.browser=" + value.agents().openLoginInBrowser() + "\n"
                + "agents.restore.last.chat=" + value.agents().restoreLastChat() + "\n"
                + "agents.display.messages=" + value.agents().displayMessageLimit() + "\n"
                + "agents.display.characters.mib=" + value.agents().displayCharacterLimitMiB() + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("=", "\\=").replace(":", "\\:"); }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null) throw new IllegalArgumentException("Missing preference: " + name);
        return value;
    }

    private static int integer(Properties properties, String name) {
        try { return Integer.parseInt(required(properties, name)); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid number: " + name); }
    }

    private static int optionalInteger(Properties properties, String name, int fallback) {
        return properties.containsKey(name) ? integer(properties, name) : fallback;
    }

    private static boolean optionalBool(Properties properties, String name, boolean fallback) {
        return properties.containsKey(name) ? bool(properties, name) : fallback;
    }

    private static boolean bool(Properties properties, String name) {
        String value = required(properties, name);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean: " + name);
        return Boolean.parseBoolean(value);
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
