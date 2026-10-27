package dev.rilvune.app.application;

import dev.rilvune.document.application.*;
import dev.rilvune.document.domain.*;
import dev.rilvune.document.infrastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FileOpenCoordinatorTest {
    @TempDir Path directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Queue<Runnable> presentation = new ArrayDeque<>();
    private final CompletableFuture<DocumentHandle> arrival = new CompletableFuture<>();
    private DocumentService documents;
    private FileOpenCoordinator opens;
    private FileDraftRepository storage;
    private boolean failRelease;

    @BeforeEach void setup() throws Exception {
        storage = new FileDraftRepository(directory.resolve("drafts"));
        DraftRepository repository = new DraftRepository() {
            public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            public void save(RecoveryDraft draft) throws IOException { storage.save(draft); }
            public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            public void remove(UUID id) throws IOException { storage.remove(id); }
            public void release(UUID id) throws IOException {
                if (failRelease) throw new IOException("Release unavailable");
                storage.release(id);
            }
        };
        documents = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, Runnable::run, Duration.ofDays(1));
        opens = new FileOpenCoordinator(path -> arrival, presentation::add);
    }
    @AfterEach void teardown() throws Exception {
        failRelease = false;
        documents.close().get(5, TimeUnit.SECONDS);
        scheduler.shutdownNow(); io.close();
    }
    private <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    private DocumentHandle draft() { return documents.retain(documents.createDraft()); }

    @Test void transitionDrainsALateSuccessfulAcquisitionAndReleasesItsRecoveryClaim() throws Exception {
        DocumentHandle handle = draft();
        var opening = opens.open(directory, ignored -> { fail("Obsolete presentation"); return null; });
        var transition = opens.quiesce(); assertFalse(transition.isDone());
        arrival.complete(handle); assertFalse(transition.isDone());
        presentation.remove().run(); await(transition); await(opening);
        assertTrue(documents.sessions().isEmpty()); assertTrue(handle.isReleased());
        var another = new FileDraftRepository(directory.resolve("drafts"));
        assertTrue(another.claim(handle.session().id())); another.release(handle.session().id());
    }

    @Test void staleOpeningReleasesOnlyItsOwnConsumer() throws Exception {
        DocumentHandle other = draft(), request = documents.retain(other.session());
        var opening = opens.open(directory, ignored -> { fail(); return null; });
        var transition = opens.quiesce(); arrival.complete(request); presentation.remove().run();
        await(transition); await(opening);
        assertFalse(other.session().isClosed()); other.session().replaceText("Other consumer still edits");
        await(other.release());
    }

    @Test void failedCleanupBlocksTransitionAndCanBeRetriedWithoutReopening() throws Exception {
        DocumentHandle handle = draft(); failRelease = true;
        var opening = opens.open(directory, ignored -> { fail(); return null; });
        var transition = opens.quiesce(); arrival.complete(handle); presentation.remove().run();
        assertThrows(ExecutionException.class, () -> await(transition));
        assertThrows(ExecutionException.class, () -> await(opening));
        assertFalse(handle.isReleased()); assertFalse(handle.session().isClosed());
        failRelease = false; await(opens.quiesce()); assertTrue(handle.isReleased());
        opens.resume();
        var fresh = new FileOpenCoordinator(path -> CompletableFuture.completedFuture(draft()), Runnable::run);
        assertEquals("ok", await(fresh.open(directory, ignored -> new FileOpenCoordinator.Adoption<>("ok", false))));
    }

    @Test void presentationFailureAndRejectedDispatchReleaseTheAcquisition() throws Exception {
        DocumentHandle handle = draft();
        var opening = opens.open(directory, ignored -> { throw new IllegalStateException("UI failed"); });
        arrival.complete(handle); presentation.remove().run();
        assertThrows(ExecutionException.class, () -> await(opening)); assertTrue(handle.isReleased());
        DocumentHandle rejected = draft();
        var owner = new FileOpenCoordinator(path -> CompletableFuture.completedFuture(rejected), task -> { throw new RejectedExecutionException(); });
        assertThrows(ExecutionException.class, () -> await(owner.open(directory, ignored -> null)));
        assertTrue(rejected.isReleased());
    }

    @Test void observerCancellationCannotLoseALateHandle() throws Exception {
        DocumentHandle handle = draft();
        var observer = opens.open(directory, ignored -> new FileOpenCoordinator.Adoption<>("existing tab", false));
        observer.cancel(false); var transition = opens.quiesce();
        arrival.complete(handle); presentation.remove().run(); await(transition);
        assertTrue(handle.isReleased());
    }

    @Test void transferAndDuplicateAcquisitionKeepTheTabConsumerAlive() throws Exception {
        DocumentHandle tab = draft();
        var first = opens.open(directory, ignored -> new FileOpenCoordinator.Adoption<>("tab", true));
        arrival.complete(tab); presentation.remove().run(); assertEquals("tab", await(first));
        await(opens.quiesce()); assertFalse(tab.isReleased()); opens.resume();
        var duplicate = new FileOpenCoordinator(path -> CompletableFuture.completedFuture(documents.retain(tab.session())), Runnable::run);
        await(duplicate.open(directory, ignored -> new FileOpenCoordinator.Adoption<>("tab", false)));
        assertFalse(tab.session().isClosed()); await(tab.release());
    }

    @Test void ordinaryLoadFailureDoesNotBlockLeavingTheProject() throws Exception {
        var opening = opens.open(directory, ignored -> null);
        var transition = opens.quiesce(); arrival.completeExceptionally(new IOException("Missing file"));
        await(transition); assertThrows(ExecutionException.class, () -> await(opening));
        opens.resume();
    }
}
