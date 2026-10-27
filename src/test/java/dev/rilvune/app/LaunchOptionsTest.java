package dev.rilvune.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LaunchOptionsTest {
    @Test
    void welcomeCanStartWithoutWorkspaceAndMisspelledOrDuplicateOptionsFail() {
        org.junit.jupiter.api.Assertions.assertNull(LaunchOptions.parse(new String[] {}).workspace());
        assertEquals(Path.of(".local").toAbsolutePath().normalize(), LaunchOptions.parse(new String[] {}).profile());
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[] {"--workspce", "."}));
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[] {"--workspace", "--snapshot", "image.png"}));
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[] {"--workspace", ".", "--workspace", ".."}));
    }

    @Test
    void pathsWithSpacesArePreservedAndOpenRemainsRelativeToWorkspace() {
        LaunchOptions options = LaunchOptions.parse(new String[] {"--workspace", "project with spaces", "--open", "src/My File.java"});
        assertEquals(Path.of("project with spaces").toAbsolutePath().normalize(), options.workspace());
        assertEquals(Path.of("src/My File.java"), options.openFile().orElseThrow());
    }
    @Test void comparisonRequiresAnExplicitLeftSourceAndPreservesBothPaths() {
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[]{"--compare", "other.txt"}));
        LaunchOptions options = LaunchOptions.parse(new String[]{"--open", "left file.txt", "--compare", "right file.txt"});
        assertEquals(Path.of("left file.txt"), options.openFile().orElseThrow());
        assertEquals(Path.of("right file.txt"), options.compareFile().orElseThrow());
    }
}
