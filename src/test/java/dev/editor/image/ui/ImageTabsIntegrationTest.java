package dev.editor.image.ui;

import dev.editor.editor.ui.EditorTabs;
import dev.editor.image.domain.ImageTabState;
import dev.editor.image.infrastructure.*;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.settings.domain.EditorSettings;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(20)
class ImageTabsIntegrationTest {
    @TempDir Path directory;
    @ParameterizedTest
    @CsvSource({
            "png, Read-only",
            "bmp, Read-only",
            "gif, Read-only · First frame only",
            "tiff, Read-only · First page only",
            "jpeg, Read-only · Stored orientation; EXIF rotation is not applied"
    })
    void previewNoticesAndTooltipsMatchDecodedFormat(String format, String expected) throws Exception {
        // Use a misleading extension to ensure notices follow decoded content, not the filename.
        Path path = directory.resolve("preview.png");
        var pixels = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertTrue(javax.imageio.ImageIO.write(pixels, format, path.toFile()));
        var tabs = edt(() -> new EditorTabs(EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { }, ignored -> { }, () -> { }, () -> { }));
        var images = edt(() -> new ImageTabs(tabs, directory, ForkJoinPool.commonPool(), EditorTheme::graphite));
        try {
            edt(() -> images.open(path, "image:notice", true)).get(5, TimeUnit.SECONDS);
            edt(() -> {
                var view = (ImageTabView)tabs.activeTab().orElseThrow();
                assertEquals(ImageTabState.Phase.READY, view.controller().model().state().phase());
                assertEquals(expected, view.controller().model().state().message());
                var toolbar = (JPanel)((java.awt.BorderLayout)view.getLayout()).getLayoutComponent(java.awt.BorderLayout.NORTH);
                var metadata = java.util.Arrays.stream(toolbar.getComponents())
                        .filter(component -> component instanceof JLabel label && label.getText().contains("10 × 10"))
                        .map(component -> (JLabel)component).findFirst().orElseThrow();
                assertEquals(expected, metadata.getToolTipText());
                return null;
            });
        } finally { edt(() -> { tabs.disposeAfterShutdown(); images.close(); return null; }); }
    }
    @Test void canceledDecoderRetainsItsSlotAndPixelsUntilRealExitAndNeverReopensClosedTab() throws Exception {
        var budget = new ImageMemoryBudget(128L << 20);
        var real = new ImageFileLoader(directory, new ImageFileLoader.Limits(4096, 100, 10000), budget);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        Path first = image("first.png"), second = image("second.png");
        ImageFileDecoder ignoringCancellation = (path, canceled) -> {
            var loaded = real.load(path, () -> false);
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException failure) { loaded.close(); throw new java.io.IOException(failure); }
            }
            return loaded;
        };
        var tabs = edt(() -> new EditorTabs(EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { }, ignored -> { }, () -> { }, () -> { }));
        var images = edt(() -> new ImageTabs(tabs, ignoringCancellation, ForkJoinPool.commonPool(), EditorTheme::graphite));
        try {
            var loading = edt(() -> images.open(first, "image:first", true));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(10 * 10 * 4, budget.used());
            assertTrue(edt(() -> tabs.requestClose("image:first")).get(5, TimeUnit.SECONDS));
            assertTrue(loading.isDone()); assertTrue(edt(() -> tabs.tabs().isEmpty()));
            assertEquals(10 * 10 * 4, budget.used(), "Canceled worker still owns its bitmap");
            var next = edt(() -> images.open(second, "image:second", true));
            assertEquals(1, calls.get()); assertFalse(next.isDone());
            release.countDown(); next.get(5, TimeUnit.SECONDS);
            assertEquals(1, edt(() -> tabs.tabs().size())); assertEquals("image:second", edt(tabs::activeId));
            assertEquals(ImageTabState.Phase.READY, edt(() -> ((ImageTabView)tabs.activeTab().orElseThrow()).controller().model().state().phase()));
            assertEquals(10 * 10 * 4, budget.used());
            edt(() -> {
                tabs.requestClose("image:second");
                return images.open(second, "image:reopened", true);
            }).get(5, TimeUnit.SECONDS);
            assertEquals("image:reopened", edt(tabs::activeId));
            assertEquals(1, edt(() -> tabs.tabs().size()));
            edt(tabs::closeAll).get(5, TimeUnit.SECONDS); assertEquals(0, budget.used());
        } finally { release.countDown(); edt(() -> { images.close(); tabs.disposeAfterShutdown(); return null; }); }
    }
    @Test void renameDeleteAndExternalChangesUpdateReadOnlyModelWithoutDocumentLifecycle() throws Exception {
        var tabs = edt(() -> new EditorTabs(EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { }, ignored -> { }, () -> { }, () -> { }));
        var images = edt(() -> new ImageTabs(tabs, directory, ForkJoinPool.commonPool(), EditorTheme::graphite));
        Path source = image("source.png"), target = directory.resolve("renamed.png");
        try {
            edt(() -> images.open(source, "image:stable", true)).get(5, TimeUnit.SECONDS);
            var view = edt(() -> (ImageTabView)tabs.activeTab().orElseThrow());
            Files.move(source, target);
            var moved = new dev.editor.workspace.application.WorkspaceMutationCoordinator.Result(true, true, target, java.util.Set.of(), java.util.Set.of(), null);
            edt(() -> { images.mutation(dev.editor.workspace.application.WorkspaceMutationCoordinator.Kind.MOVE, source, moved); return null; });
            awaitReady(view); assertEquals(target, edt(() -> view.controller().model().state().path()));
            assertEquals("image:stable", edt(tabs::activeId));
            Files.write(target, new byte[]{1, 2, 3});
            edt(() -> { view.controller().checkForChanges(); return null; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!edt(() -> view.controller().model().state().stale())) { if (System.nanoTime() > deadline) fail("Change check did not mark stale"); Thread.sleep(10); }
            Files.delete(target);
            var deleted = new dev.editor.workspace.application.WorkspaceMutationCoordinator.Result(true, true, null, java.util.Set.of(), java.util.Set.of(), null);
            edt(() -> { images.mutation(dev.editor.workspace.application.WorkspaceMutationCoordinator.Kind.DELETE, target, deleted); return null; });
            assertEquals(ImageTabState.Phase.MISSING, edt(() -> view.controller().model().state().phase()));
            assertFalse(Files.exists(target)); assertEquals(1, edt(() -> tabs.tabs().size()));
        } finally { edt(() -> { tabs.disposeAfterShutdown(); images.close(); return null; }); }
    }
    @Test void restorationBeyondQueueCapacityRegistersImmediatelyAndSurvivesProjectQuiesce() throws Exception {
        var budget = new ImageMemoryBudget(128L << 20);
        var real = new ImageFileLoader(directory, new ImageFileLoader.Limits(4096, 100, 10000), budget);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        ImageFileDecoder slow = (path, canceled) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
            }
            return real.load(path, canceled);
        };
        var tabs = edt(() -> new EditorTabs(EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { }, ignored -> { }, () -> { }, () -> { }));
        var images = edt(() -> new ImageTabs(tabs, slow, ForkJoinPool.commonPool(), EditorTheme::graphite));
        try {
            Path first = image("restored-0.png");
            assertEquals("image:0", edt(() -> images.restore(first, "image:0")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int index = 1; index < 22; index++) {
                Path path = image("restored-" + index + ".png"); String id = "image:" + index;
                assertEquals(id, edt(() -> images.restore(path, id)));
            }
            assertEquals(22, edt(() -> tabs.tabs().size())); assertEquals(1, calls.get());
            assertTrue(edt(images::quiesce).isDone());
            edt(() -> { images.resume(); return null; }); release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!edt(() -> tabs.tabs().stream().allMatch(tab -> ((ImageTabView)tab).controller().model().state().phase() == ImageTabState.Phase.READY))) {
                if (System.nanoTime() > deadline) fail("Deferred restored images did not finish"); Thread.sleep(10);
            }
            edt(tabs::closeAll).get(5, TimeUnit.SECONDS); assertEquals(0, budget.used());
        } finally { release.countDown(); edt(() -> { images.close(); tabs.disposeAfterShutdown(); return null; }); }
    }
    @Test void resumingAnAlreadyActiveWorkspaceDoesNotCancelOrRestartItsImageLoad() throws Exception {
        var budget = new ImageMemoryBudget(128L << 20);
        var real = new ImageFileLoader(directory, new ImageFileLoader.Limits(4096, 100, 10000), budget);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        ImageFileDecoder slow = (path, canceled) -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
            return real.load(path, canceled);
        };
        var tabs = edt(() -> new EditorTabs(EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { }, ignored -> { }, () -> { }, () -> { }));
        var images = edt(() -> new ImageTabs(tabs, slow, ForkJoinPool.commonPool(), EditorTheme::graphite));
        try {
            Path path = image("resume.png"); var loading = edt(() -> images.open(path, "image:resume", true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var view = edt(() -> (ImageTabView)tabs.activeTab().orElseThrow());
            long generation = edt(() -> view.controller().model().state().generation());
            edt(() -> { images.resume(); images.resume(); return null; });
            assertFalse(loading.isDone(), "Resume must leave the original load pending");
            assertEquals(generation, edt(() -> view.controller().model().state().generation()));
            release.countDown(); loading.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); assertEquals(ImageTabState.Phase.READY, edt(() -> view.controller().model().state().phase()));
        } finally { release.countDown(); edt(() -> { images.close(); tabs.disposeAfterShutdown(); return null; }); }
    }
    private void awaitReady(ImageTabView view) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (edt(() -> view.controller().model().state().phase()) == ImageTabState.Phase.LOADING) { if (System.nanoTime() > deadline) fail("Image did not load"); Thread.sleep(10); }
        assertEquals(ImageTabState.Phase.READY, edt(() -> view.controller().model().state().phase()));
    }
    private Path image(String name) throws Exception { Path path = directory.resolve(name); javax.imageio.ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", path.toFile()); return path; }
    private static <T> T edt(Callable<T> call) throws Exception { var task = new FutureTask<>(call); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS); }
}
