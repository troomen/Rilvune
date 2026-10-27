package dev.rilvune.search.application;

import dev.rilvune.document.application.DocumentService;
import dev.rilvune.workspace.domain.Workspace;
import dev.rilvune.workspace.domain.WorkspaceEntry;
import dev.rilvune.workspace.domain.WorkspaceFiles;
import dev.rilvune.workspace.domain.TextFile;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import dev.rilvune.workspace.infrastructure.NioWorkspaceFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSearchTest {
    @TempDir Path directory;
    @Test void unicodeSearchOffsetsAndAllOccurrencesMatchNormalizedPreview() throws Exception {
        Path file = Files.writeString(directory.resolve("notes.txt"), "\uFEFFİ Привет ПРИВЕТ\r\nПривет\r\n");
        ProjectSearch search = new ProjectSearch(new NioWorkspaceFiles(10000), 100, Set.of());
        var results = search.search(new Workspace(directory), "привет", false, false, () -> false);
        assertEquals(3, results.hits().size()); assertEquals(List.of(1, 1, 2), results.hits().stream().map(ProjectSearch.Hit::line).toList());
        String preview = search.readPreview(new Workspace(directory), file);
        for (var hit : results.hits()) assertTrue(preview.regionMatches(true, hit.offset(), "привет", 0, 6));
    }
    @Test void excludesHeavyDirectoriesAndStopsAtExplicitLimit() throws Exception {
        Files.createDirectory(directory.resolve("build")); Files.writeString(directory.resolve("build/hidden.txt"), "needle"); Files.writeString(directory.resolve("visible.txt"), "needle needle needle");
        var result = new ProjectSearch(new NioWorkspaceFiles(10000), 2, Set.of("build")).search(new Workspace(directory), "needle", false, true, () -> false);
        assertEquals(2, result.hits().size()); assertTrue(result.limited()); assertTrue(result.hits().stream().allMatch(hit -> hit.path().getFileName().toString().equals("visible.txt")));
    }
    @Test void cancellationDuringMatchingRetainsOnlyCompletedFileResults() throws Exception {
        Path first = Files.writeString(directory.resolve("a.txt"), "needle");
        Path second = Files.writeString(directory.resolve("b.txt"), "needle needle");
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var delegate = new NioWorkspaceFiles(10000);
        WorkspaceFiles files = new WorkspaceFiles() {
            @Override public Workspace openWorkspace(Path root) throws java.io.IOException { return delegate.openWorkspace(root); }
            @Override public List<WorkspaceEntry> list(Workspace workspace, Path path) throws java.io.IOException { return delegate.list(workspace, path); }
            @Override public TextFile read(Workspace workspace, Path path) throws java.io.IOException {
                TextFile preview = delegate.read(workspace, path);
                if (path.equals(second)) cancelled.set(true);
                return preview;
            }
        };
        var result = new ProjectSearch(files, 100, Set.of()).search(new Workspace(directory), "needle", false, true, cancelled::get);
        assertTrue(cancelled.get());
        assertEquals(List.of(first), result.hits().stream().map(ProjectSearch.Hit::path).toList());
        assertEquals(0, result.skipped()); assertFalse(result.limited());
    }
    @Test void replacementUsesAutosaveAndReleasesFinalOwnership() throws Exception {
        Path file = Files.writeString(directory.resolve("notes.txt"), "Привет привет\n");
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            DocumentService documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")), io, scheduler, Runnable::run, Duration.ofDays(1));
            try {
                ProjectReplacement replacement = new ProjectReplacement(documents, Runnable::run);
                var plan = replacement.prepare(List.of(new ProjectSearch.Hit(file, 1, "", 0)), "привет", "Здравствуйте", false, false).get(5, TimeUnit.SECONDS);
                assertEquals(2, plan.matches()); replacement.apply(plan).get(5, TimeUnit.SECONDS);
                assertEquals("Здравствуйте Здравствуйте\n", Files.readString(file)); assertTrue(documents.sessions().isEmpty());
            } finally { documents.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); }
        }
    }
    @Test void changedPlanIsRejectedBeforeAnyFileIsEdited() throws Exception {
        Path a = Files.writeString(directory.resolve("a.txt"), "needle"); Path b = Files.writeString(directory.resolve("b.txt"), "needle");
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            DocumentService documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")), io, scheduler, Runnable::run, Duration.ofDays(1));
            try {
                var opened = documents.open(b).get(5, TimeUnit.SECONDS); ProjectReplacement replacement = new ProjectReplacement(documents, Runnable::run);
                var plan = replacement.prepare(List.of(new ProjectSearch.Hit(a, 1, ""), new ProjectSearch.Hit(b, 1, "")), "needle", "updated", true, false).get(5, TimeUnit.SECONDS);
                opened.replaceText("newer edit"); assertFalse(replacement.apply(plan).get(5, TimeUnit.SECONDS).successful());
                assertEquals("needle", Files.readString(a)); assertEquals("newer edit", opened.text());
            } finally { documents.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); }
        }
    }
    @Test void closingDocumentInvalidatesWholeReplacementPlanBeforeAnyEdit() throws Exception {
        Path a = Files.writeString(directory.resolve("a.txt"), "needle");
        Path b = Files.writeString(directory.resolve("b.txt"), "needle");
        ExecutorService io = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DocumentService documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")), io, scheduler, Runnable::run, Duration.ofDays(1));
        CountDownLatch release = new CountDownLatch(1);
        try {
            var first = documents.open(a).get(5, TimeUnit.SECONDS);
            var second = documents.open(b).get(5, TimeUnit.SECONDS);
            ProjectReplacement replacement = new ProjectReplacement(documents, Runnable::run);
            var plan = replacement.prepare(List.of(new ProjectSearch.Hit(a, 1, ""), new ProjectSearch.Hit(b, 1, "")), "needle", "updated", true, false).get(5, TimeUnit.SECONDS);
            CountDownLatch entered = new CountDownLatch(1);
            io.execute(() -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            second.close();
            assertTrue(second.isClosing()); assertFalse(second.isClosed());
            var rejected = replacement.apply(plan);
            assertEquals("needle", first.text(), "A closing target must reject the entire plan before editing another buffer");
            release.countDown();
            assertFalse(rejected.get(5, TimeUnit.SECONDS).successful());
            assertEquals("needle", Files.readString(a));
        } finally { release.countDown(); documents.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); io.close(); }
    }

}
