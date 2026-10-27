package dev.rilvune.comparison.application;

import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import dev.rilvune.session.domain.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FileComparisonServiceTest {
    @TempDir Path directory;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private DocumentService documents;
    private FileComparisonService comparisons;
    private Path root;

    @BeforeEach void setup() throws Exception {
        root = Files.createDirectory(directory.resolve("project"));
        documents = new DocumentService(new NioDocumentStore(1024 * 1024, directory.resolve("locks"), root),
                new FileDraftRepository(directory.resolve("drafts")), workers, scheduler, Runnable::run, Duration.ofMillis(200));
        comparisons = new FileComparisonService(documents, root, workers);
    }
    @AfterEach void close() throws Exception {
        try { comparisons.cleanup().get(5, TimeUnit.SECONDS); documents.close().get(5, TimeUnit.SECONDS); }
        finally { scheduler.shutdownNow(); workers.close(); }
    }
    @Test void reusesLiveSourcesAndReleasesOnlyTheAcquiredConsumers() throws Exception {
        Path a = Files.writeString(root.resolve("a.txt"), "left\n"), b = Files.writeString(root.resolve("b.txt"), "right\n");
        var ordinary = documents.acquire(a).get(5, TimeUnit.SECONDS);
        var pair = comparisons.open(source(a), source(b)).get(5, TimeUnit.SECONDS);
        assertSame(ordinary.session(), pair.left().session());
        comparisons.discard(pair).get(5, TimeUnit.SECONDS);
        assertFalse(ordinary.session().isClosed()); assertTrue(pair.right().session().isClosed());
        ordinary.release().get(5, TimeUnit.SECONDS); assertTrue(ordinary.session().isClosed());
    }
    @Test void rejectsSameDocumentThroughPathsSymlinksAndHardLinksWithoutAcquiringSources() throws Exception {
        Path a = Files.writeString(root.resolve("a.txt"), "left\n");
        Path symbolic = Files.createSymbolicLink(root.resolve("symbolic.txt"), a.getFileName());
        Path hard = Files.createLink(root.resolve("hard.txt"), a);
        for (Path alias : java.util.List.of(a, symbolic, hard)) {
            assertThrows(ExecutionException.class, () -> comparisons.open(source(a), source(alias)).get(5, TimeUnit.SECONDS));
        }
        assertTrue(documents.sessions().isEmpty());
    }
    @Test void failedSecondLoadClosesTheOtherwiseUnownedFirstSource() throws Exception {
        Path a = Files.writeString(root.resolve("a.txt"), "left\n");
        Path binary = Files.write(root.resolve("binary.dat"), new byte[]{1, 0, 2});
        assertThrows(ExecutionException.class, () -> comparisons.open(source(a), source(binary)).get(5, TimeUnit.SECONDS));
        comparisons.cleanup().get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty()); assertEquals("left\n", Files.readString(a));
    }
    @Test void rejectsSourcesOutsideTheMountedRootIncludingEscapingLinks() throws Exception {
        Path a = Files.writeString(root.resolve("a.txt"), "left\n");
        Path outside = Files.writeString(directory.resolve("outside.txt"), "outside\n");
        Path link = Files.createSymbolicLink(root.resolve("escape.txt"), outside);
        for (Path path : java.util.List.of(outside, link)) assertThrows(ExecutionException.class,
                () -> comparisons.open(source(a), source(path)).get(5, TimeUnit.SECONDS));
        assertTrue(documents.sessions().isEmpty());
    }
    @Test void liveDraftsCanBeComparedWithoutManufacturingAPath() throws Exception {
        var a = documents.createDraft(); var b = documents.createDraft();
        a.replace(0, 0, "left"); b.replace(0, 0, "right");
        var left = new SessionState.Source(a.id().toString(), null); var right = new SessionState.Source(b.id().toString(), null);
        assertThrows(ExecutionException.class, () -> comparisons.open(left, left).get(5, TimeUnit.SECONDS));
        var pair = comparisons.open(left, right).get(5, TimeUnit.SECONDS);
        assertSame(a, pair.left().session()); assertSame(b, pair.right().session());
        assertNull(pair.left().session().path()); comparisons.discard(pair).get(5, TimeUnit.SECONDS);
        assertTrue(documents.sessions().isEmpty());
    }
    private SessionState.Source source(Path path) { return new SessionState.Source("", path); }
}
