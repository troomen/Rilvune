package dev.editor.document.ui;

import dev.editor.document.application.DocumentService;
import dev.editor.document.application.DocumentSession;
import dev.editor.document.domain.DraftRepository;
import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.settings.domain.EditorSettings;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(20)
class TextTabsLifecycleTest {
    @TempDir Path directory;

    @Test void bulkCloseTracksEveryTabAndNeverReenablesAnAlreadyClosedDocument() throws Exception {
        FileDraftRepository storage = new FileDraftRepository(directory.resolve("drafts"));
        AtomicBoolean rejectFinalRelease = new AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<UUID> failing = new java.util.concurrent.atomic.AtomicReference<>();
        DraftRepository repository = new DraftRepository() {
            @Override public boolean claim(UUID id) throws java.io.IOException { return storage.claim(id); }
            @Override public void save(RecoveryDraft draft) throws java.io.IOException { storage.save(draft); }
            @Override public List<RecoveryDraft> load() throws java.io.IOException { return storage.load(); }
            @Override public void remove(UUID id) throws java.io.IOException { storage.remove(id); }
            @Override public void release(UUID id) throws java.io.IOException {
                if (id.equals(failing.get()) && rejectFinalRelease.get()) throw new java.io.IOException("Simulated close failure");
                storage.release(id);
            }
        };
        ExecutorService io = Executors.newSingleThreadExecutor(); ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DocumentService documents = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler, SwingUtilities::invokeLater, Duration.ofDays(1));
        DocumentSession first = documents.open(Files.writeString(directory.resolve("first.txt"), "first")).get(5, TimeUnit.SECONDS);
        DocumentSession second = documents.open(Files.writeString(directory.resolve("second.txt"), "second")).get(5, TimeUnit.SECONDS); failing.set(second.id());
        DocumentBindings bindings = new DocumentBindings(documents);
        TextTabs tabs = edt(() -> { EditorTheme theme = EditorTheme.graphite(); theme.install(); TextTabs created = new TextTabs(bindings, theme, EditorSettings.defaults(), ignored -> { }, ignored -> { }, ignored -> { }, () -> { }, () -> { }, ignored -> { throw new AssertionError("Unexpected menu request"); }, ignored -> { }); created.show(first); created.show(second); return created; });
        var comparison = edt(() -> {
            var tab = new dev.editor.comparison.ui.ComparisonTabView("comparison:close-failure", bindings.acquire(first), bindings.acquire(second),
                    EditorSettings.defaults(), EditorTheme.graphite(), new dev.editor.diff.infrastructure.MyersDiffEngine(dev.editor.diff.infrastructure.DiffLimits.interactive()), io,
                    ignored -> { }, ignored -> { }, ignored -> { }, ignored -> { }, () -> tabs.requestClose("comparison:close-failure"), () -> { });
            tabs.register(tab, true); return tab;
        });
        Timer dismissError = edt(() -> { Timer timer = new Timer(20, event -> {
            for (Window window : Window.getWindows()) if (window instanceof JDialog dialog && "Cannot close document".equals(dialog.getTitle())) dialog.dispose();
        }); timer.start(); return timer; });
        try {
            CompletableFuture<Boolean> closed = edt(() -> {
                CompletableFuture<Boolean> pending = tabs.closeAll();
                assertTrue(tabs.pendingClose(first.id()).isPresent(), "Bulk close must participate in the reopen guard");
                assertTrue(tabs.pendingClose(second.id()).isPresent());
                return pending;
            });
            assertFalse(closed.get(5, TimeUnit.SECONDS));
            assertTrue(first.isClosed()); assertFalse(second.isClosed());
            edt(() -> {
                assertTrue(tabs.views().isEmpty(), "Released normal tabs must be removed even when a comparison is retained");
                assertEquals(List.of(comparison), tabs.tabs()); assertSame(second, comparison.rightView().session());
                assertFalse(comparison.leftView().textComponent().isEditable());
                assertTrue(comparison.rightView().textComponent().isEditable());
                comparison.rightView().textComponent().append(" still editable");
                return null;
            });
            rejectFinalRelease.set(false);
            assertTrue(edt(() -> tabs.requestClose(comparison.id())).get(5, TimeUnit.SECONDS));
            assertEquals("second still editable", Files.readString(second.path()));
            assertTrue(second.isClosed());
        } finally {
            rejectFinalRelease.set(false); edt(() -> { dismissError.stop(); return null; });
            documents.close().get(5, TimeUnit.SECONDS); scheduler.shutdownNow(); io.close();
            edt(() -> { tabs.disposeAfterShutdown(); bindings.close(); return null; });
        }
    }
    @Test void failedViewEnhancementReturnsTheConsumerWithoutLeavingBindingsOrATab() throws Exception {
        try (var io = Executors.newSingleThreadExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")),
                    io, scheduler, SwingUtilities::invokeLater, Duration.ofDays(1));
            var bindings = new DocumentBindings(documents, io);
            var handle = documents.acquire(Files.writeString(directory.resolve("open.txt"), "source")).get(5, TimeUnit.SECONDS);
            TextTabs tabs = edt(() -> new TextTabs(bindings, EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { },
                    ignored -> { }, ignored -> { }, () -> { }, () -> { }, ignored -> null, ignored -> { }));
            try {
                edt(() -> {
                    tabs.setViewEnhancer(view -> { throw new IllegalStateException("Enhancement failed"); });
                    assertThrows(IllegalStateException.class, () -> tabs.adopt(handle, true));
                    assertTrue(tabs.views().isEmpty()); assertTrue(tabs.tabs().isEmpty());
                    assertFalse(handle.isReleased()); return null;
                });
                handle.release().get(5, TimeUnit.SECONDS);
                assertTrue(documents.sessions().isEmpty());
                edt(() -> { bindings.close(); return null; });
            } finally { documents.close().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void cancelingARealTextConflictLeavesImageAndTextTabsOpen() throws Exception {
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")),
                    io, scheduler, SwingUtilities::invokeLater, Duration.ofDays(1));
            var bindings = new DocumentBindings(documents, io);
            Path text = Files.writeString(directory.resolve("conflict.txt"), "base\n");
            var session = documents.open(text).get(5, TimeUnit.SECONDS);
            var tabs = edt(() -> new TextTabs(bindings, EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { },
                    ignored -> { }, ignored -> { }, () -> { }, () -> { }, ignored -> null, ignored -> { }));
            var images = edt(() -> new dev.editor.image.ui.ImageTabs(tabs, directory, io, EditorTheme::graphite));
            Path image = directory.resolve("image.png");
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image.toFile());
            try {
                var editor = edt(() -> tabs.show(session));
                edt(() -> images.open(image, "image:cancel", true)).get(5, TimeUnit.SECONDS);
                edt(() -> { editor.textComponent().setText("local\n"); return null; }); Files.writeString(text, "disk\n");
                assertTrue(session.flush().get(5, TimeUnit.SECONDS).needsAttention());
                var closing = edt(tabs::closeAll);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!edt(() -> {
                    for (Window window : Window.getWindows()) if (window instanceof JDialog dialog && dialog.isShowing()
                            && "Unresolved document".equals(dialog.getTitle())) {
                        for (Component component : dialog.getContentPane().getComponents()) if (component instanceof JOptionPane pane) pane.setValue("Cancel");
                        return true;
                    }
                    return false;
                })) { if (System.nanoTime() > deadline) fail("Close decision did not appear"); Thread.sleep(10); }
                assertFalse(closing.get(5, TimeUnit.SECONDS));
                assertEquals(2, edt(() -> tabs.tabs().size()));
                assertTrue(edt(() -> editor.textComponent().isEditable()));
                assertFalse(session.isClosed());
                assertEquals(dev.editor.image.domain.ImageTabState.Phase.READY,
                        edt(() -> ((dev.editor.image.ui.ImageTabView)tabs.activeTab().orElseThrow()).controller().model().state().phase()));
                assertEquals("disk\n", Files.readString(text));
                session.acceptDisk().get(5, TimeUnit.SECONDS);
                assertTrue(edt(tabs::closeAll).get(5, TimeUnit.SECONDS));
            } finally {
                edt(() -> { for (Window window : Window.getWindows()) if (window instanceof JDialog dialog) dialog.dispose(); return null; });
                documents.close().get(5, TimeUnit.SECONDS);
                edt(() -> { tabs.disposeAfterShutdown(); images.close(); bindings.close(); return null; });
            }
        }
    }

    @Test void failedSessionPresentationRetainsFailedCleanupForLifecycleRetry() throws Exception {
        try (var io = Executors.newVirtualThreadPerTaskExecutor(); var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var storage = new FileDraftRepository(directory.resolve("drafts"));
            var failRelease = new AtomicBoolean(true);
            var cleanupReported = new CountDownLatch(1);
            DraftRepository repository = new DraftRepository() {
                public boolean claim(UUID id) throws java.io.IOException { return storage.claim(id); }
                public void save(RecoveryDraft draft) throws java.io.IOException { storage.save(draft); }
                public List<RecoveryDraft> load() throws java.io.IOException { return storage.load(); }
                public void remove(UUID id) throws java.io.IOException { storage.remove(id); }
                public void release(UUID id) throws java.io.IOException {
                    if (failRelease.get()) throw new java.io.IOException("Simulated presentation cleanup failure");
                    storage.release(id);
                }
            };
            var documents = new DocumentService(new NioDocumentStore(10000), repository,
                    io, scheduler, SwingUtilities::invokeLater, Duration.ofDays(1));
            var bindings = new DocumentBindings(documents, io);
            Path file = Files.writeString(directory.resolve("presentation.txt"), "source");
            var session = documents.open(file).get(5, TimeUnit.SECONDS);
            var tabs = edt(() -> new TextTabs(bindings, EditorTheme.graphite(), EditorSettings.defaults(), message -> {
                        if (message.startsWith("Cannot release failed text view:")) cleanupReported.countDown();
                    }, ignored -> { }, ignored -> { }, () -> { }, () -> { }, ignored -> null, ignored -> { }));
            try {
                edt(() -> {
                    tabs.setViewEnhancer(view -> { throw new IllegalStateException("Cannot create editor extension"); });
                    assertThrows(IllegalStateException.class, () -> tabs.show(session));
                    assertTrue(tabs.tabs().isEmpty()); return null;
                });
                assertTrue(cleanupReported.await(5, TimeUnit.SECONDS));
                failRelease.set(false);
                edt(tabs::quiesce).get(5, TimeUnit.SECONDS);
                assertTrue(documents.sessions().isEmpty());
                var reopened = documents.acquire(file).get(5, TimeUnit.SECONDS);
                reopened.release().get(5, TimeUnit.SECONDS); assertTrue(documents.sessions().isEmpty());
                assertEquals("source", Files.readString(file));
            } finally {
                failRelease.set(false);
                documents.close().get(5, TimeUnit.SECONDS);
                edt(() -> { tabs.disposeAfterShutdown(); bindings.close(); return null; });
            }
        }
    }

    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS); }
}
