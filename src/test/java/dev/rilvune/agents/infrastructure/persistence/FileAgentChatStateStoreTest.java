package dev.rilvune.agents.infrastructure.persistence;

import dev.rilvune.agents.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FileAgentChatStateStoreTest {
    @TempDir Path temporary;

    @Test void preferencesSurviveRecreationAndAreIsolatedByNormalizedProject() throws Exception {
        Path profile = temporary.resolve("profile"), project = temporary.resolve("project");
        var state = new AgentChatState("native-id", Map.of("native-id", new AgentChatState.Preference("Investigation", "Unsent черновик")));
        new FileAgentChatStateStore(profile).save(project, state);
        var reopened = new FileAgentChatStateStore(profile);
        assertEquals(state, reopened.load(project.resolve("child/..")));
        assertEquals(AgentChatState.empty(), reopened.load(temporary.resolve("other")));
        Path directory = profile.resolve("agents/chats");
        try (var files = Files.list(directory)) {
            var entries = files.toList(); assertEquals(1, entries.size());
            assertFalse(Files.readString(entries.getFirst()).contains("original answer"));
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(entries.getFirst()));
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            }
        }
    }
    @Test void unreadableOrUnknownPreferencesArePreservedInsteadOfBeingOverwritten() throws Exception {
        Path profile = temporary.resolve("profile"), project = temporary.resolve("project");
        var store = new FileAgentChatStateStore(profile); store.save(project, AgentChatState.empty());
        Path file;
        try (var files = Files.list(profile.resolve("agents/chats"))) { file = files.findFirst().orElseThrow(); }
        String valid = Files.readString(file);
        for (String invalid : new String[] { "broken json", valid.replace("\"schema\": 1", "\"schema\": 999"), valid.replace("\"selectedChat\": \"\"", "\"selectedChat\": null") }) {
            Files.writeString(file, invalid);
            assertThrows(Exception.class, () -> store.load(project));
            assertThrows(Exception.class, () -> store.save(project, AgentChatState.empty()));
            assertEquals(invalid, Files.readString(file));
        }
    }
}
