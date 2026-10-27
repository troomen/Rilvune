package dev.rilvune.agents.infrastructure.opencode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeRuntimePathsTest {
    @TempDir Path profile;

    @Test void everyManagedPathAndChildEnvironmentBelongsToTheSelectedProfile() throws Exception {
        var paths = new OpenCodeRuntimePaths(profile); var environment = paths.environment();
        assertEquals(profile.resolve("opencode"), paths.root());
        assertEquals(paths.root().resolve("bin"), paths.bin());
        assertEquals(paths.data().toString(), environment.get("XDG_DATA_HOME"));
        assertEquals(paths.config().toString(), environment.get("OPENCODE_CONFIG_DIR"));
        assertEquals(paths.cache().toString(), environment.get("XDG_CACHE_HOME"));
        assertEquals(paths.state().toString(), environment.get("XDG_STATE_HOME"));
        assertEquals("true", environment.get("OPENCODE_DISABLE_AUTOUPDATE"));
        if (Files.getFileStore(paths.root()).supportsFileAttributeView("posix"))
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(paths.root()));
        for (String name : new String[]{"data", "config", "cache", "state"}) assertTrue(Files.isDirectory(paths.root().resolve(name)));
        assertNotEquals(new OpenCodeRuntimePaths(profile.resolve("other")).root(), paths.root());
    }
}
