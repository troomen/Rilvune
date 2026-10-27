package dev.editor.session.infrastructure;

import dev.editor.session.domain.SessionState;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SessionRepositoryTest {
    @TempDir Path directory;
    @Test void persistsTabsCaretActiveDocumentWindowAndProjectLayout() throws Exception {
        Path project = directory.resolve("проект"); SessionRepository repository = new SessionRepository(directory.resolve("profile"));
        SessionState expected = new SessionState(project, List.of(new SessionState.TextTabDescriptor("draft-id", null, 4), new SessionState.TextTabDescriptor("file-id", project.resolve("notes.txt"), 23)), "file-id", 40, 50, 1200, 800, 0.31, false, true);
        repository.save(expected); assertEquals(expected, new SessionRepository(directory.resolve("profile")).load(project));
    }
    @Test void recentProjectsDeduplicateAndCanBeRemovedAfterRestart() throws Exception {
        Path a = directory.resolve("a"); Path b = directory.resolve("b"); SessionRepository repository = new SessionRepository(directory.resolve("profile"));
        repository.remember(a); repository.remember(b); repository.remember(a);
        SessionRepository restarted = new SessionRepository(directory.resolve("profile")); assertEquals(List.of(a, b), restarted.recentProjects());
        restarted.forget(a); assertEquals(List.of(b), repository.recentProjects());
    }
    @Test void comparisonDescriptorsAndMixedTabOrderRoundTripWithoutCopyingSourceText() throws Exception {
        Path project = directory.resolve("project"); SessionRepository repository = new SessionRepository(directory.resolve("profile"));
        var comparison = new SessionState.ComparisonTabDescriptor("comparison:one", new SessionState.Source("a", project.resolve("a.txt")),
                new SessionState.Source("draft", null), 4, 8, 90, 110, true, true);
        var state = new SessionState(project, List.of(new SessionState.TextTabDescriptor("a", project.resolve("a.txt"), 7), comparison), "comparison:one",
                20, 30, 1200, 800, .3, true, false, List.of("a", "comparison:one"));
        repository.save(state);
        assertEquals(state, new SessionRepository(directory.resolve("profile")).load(project));
    }
    @Test void imagesAndExistingTextAndComparisonsRoundTripWithOneDescriptorList() throws Exception {
        Path project = directory.resolve("mixed"); var repository = new SessionRepository(directory.resolve("profile"));
        var image = new SessionState.ImageTabDescriptor("image:one", project.resolve("picture.png"));
        var text = new SessionState.TextTabDescriptor("text", project.resolve("notes.txt"), 8);
        var comparison = new SessionState.ComparisonTabDescriptor("comparison:one", new SessionState.Source("text", text.path()), new SessionState.Source("other", project.resolve("other.txt")), 1, 2, 3, 4, false, true);
        var state = new SessionState(project, List.of(text, comparison, image), image.id(), 20, 30, 1200, 800, .3, true, false,
                List.of(image.id(), text.id(), comparison.id()));
        repository.save(state); assertEquals(state, repository.load(project)); assertEquals(1, repository.load(project).images().size());
    }
    @Test void malformedOptionalImageDoesNotDiscardHealthyTextOrRewriteStoredState() throws Exception {
        Path profile = directory.resolve("profile"), project = directory.resolve("project");
        var expected = new SessionState(project, List.of(new SessionState.TextTabDescriptor("text", project.resolve("note.txt"), 7)),
                "text", 20, 30, 1200, 800, .3, true, false);
        var repository = new SessionRepository(profile); repository.save(expected);
        Path stored;
        try (var files = java.nio.file.Files.walk(profile)) { stored = files.filter(path -> path.toString().endsWith(".properties")).findFirst().orElseThrow(); }
        var data = new java.util.Properties();
        try (var input = java.nio.file.Files.newInputStream(stored)) { data.load(input); }
        data.setProperty("image.count", "1"); data.setProperty("image.0.id", " "); data.setProperty("image.0.path", project.resolve("broken.png").toString());
        try (var output = java.nio.file.Files.newOutputStream(stored)) { data.store(output, "Malformed preview descriptor"); }
        byte[] original = java.nio.file.Files.readAllBytes(stored);
        assertEquals(expected, repository.load(project)); assertArrayEquals(original, java.nio.file.Files.readAllBytes(stored));
    }
    @Test void unsupportedSessionSchemaIsRejectedWithoutRewritingTheFile() throws Exception {
        Path profile = directory.resolve("profile"), project = directory.resolve("project");
        SessionState expected = new SessionState(project, List.of(new SessionState.TextTabDescriptor("old-id", project.resolve("a.txt"), 9)),
                "old-id", 20, 30, 1200, 800, .3, true, false);
        new SessionRepository(profile).save(expected);
        Path stored;
        try (var paths = java.nio.file.Files.walk(profile)) { stored = paths.filter(path -> path.toString().endsWith(".properties")).findFirst().orElseThrow(); }
        var data = new java.util.Properties();
        try (var input = java.nio.file.Files.newInputStream(stored)) { data.load(input); }
        data.setProperty("schema", "1");
        try (var output = java.nio.file.Files.newOutputStream(stored)) { data.store(output, "Unsupported session schema"); }
        byte[] original = java.nio.file.Files.readAllBytes(stored);
        var failure = assertThrows(java.io.IOException.class, () -> new SessionRepository(profile).load(project));
        assertTrue(failure.getMessage().contains("Unsupported session schema"));
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(stored));
    }
}
