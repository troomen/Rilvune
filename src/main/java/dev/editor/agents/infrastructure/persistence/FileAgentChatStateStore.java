package dev.editor.agents.infrastructure.persistence;

import com.google.gson.*;
import dev.editor.agents.domain.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Atomic editor preferences. Saved transcripts and resumable context remain exclusively in OpenCode. */
public final class FileAgentChatStateStore implements AgentChatStateStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path directory;
    private final Gson json = new GsonBuilder().setPrettyPrinting().create();
    public FileAgentChatStateStore(Path profile) { directory = profile.toAbsolutePath().normalize().resolve("agents/chats"); }

    @Override public synchronized AgentChatState load(Path project) throws Exception {
        Path file = file(project); if (!Files.exists(file)) return AgentChatState.empty();
        if (Files.size(file) > MAX_BYTES) throw new IOException("Chat preferences exceed the storage limit");
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("Chat preferences exceed the storage limit");
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonObject data = JsonParser.parseString(source).getAsJsonObject();
            if (data.get("schema").getAsInt() != 1 || !data.get("project").getAsString().equals(key(project)))
                throw new IOException("Unsupported chat preferences format");
            AgentChatState state = json.fromJson(data.get("state"), AgentChatState.class); validate(state); return state;
        } catch (RuntimeException invalid) { throw new IOException("Chat preferences could not be read; the original file was preserved", invalid); }
    }
    @Override public synchronized void save(Path project, AgentChatState state) throws Exception {
        validate(state); Files.createDirectories(directory);
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) {
            Files.setPosixFilePermissions(directory.getParent(), PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        // Never replace an unreadable/unknown file with an empty preference snapshot.
        load(project);
        JsonObject data = new JsonObject(); data.addProperty("schema", 1); data.addProperty("project", key(project)); data.add("state", json.toJsonTree(state));
        byte[] bytes = json.toJson(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Chat preferences exceed the storage limit");
        Path temporary = posix ? Files.createTempFile(directory, "chat-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(directory, "chat-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temporary, file(project), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file(project), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void validate(AgentChatState state) throws IOException {
        if (state == null || state.selectedChat() == null || state.selectedChat().length() > 512 || state.preferences().size() > 1000)
            throw new IOException("Invalid chat preferences");
        for (var entry : state.preferences().entrySet()) {
            var value = entry.getValue();
            if (value == null || entry.getKey().isBlank() || entry.getKey().length() > 512 || value.title() == null || value.title().length() > 200
                    || value.draft() == null || value.draft().length() > 1024 * 1024) throw new IOException("Invalid chat preference entry");
        }
    }
    private Path file(Path project) throws Exception { return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key(project).getBytes(StandardCharsets.UTF_8))) + ".json"); }
    private static String key(Path project) { return project.toAbsolutePath().normalize().toString(); }
}
