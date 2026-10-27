package dev.editor.git.infrastructure;
import dev.editor.git.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PropertiesGitViewStateStoreTest {
    @TempDir Path profile;
    @Test void roundTripOutsideRepositoryAndCorruptInput() throws Exception {
        var store = new PropertiesGitViewStateStore(profile); assertEquals(GitViewState.defaults(), store.load());
        var state = new GitViewState(10, 20, 1200, 800, true, true, .6, .2, .7, .3, false, true, true, true, true, true); store.save(state); assertEquals(state, store.load());
        try (var entries = Files.list(profile)) { assertEquals(1, entries.count()); }
        Files.writeString(profile.resolve("git-view.properties"), "vertical=NaN\n"); assertThrows(java.io.IOException.class, store::load);
    }
}
