package dev.editor.image.ui;

import dev.editor.editor.application.*;
import dev.editor.image.domain.*;
import dev.editor.image.infrastructure.ImageFileLoader;
import dev.editor.image.infrastructure.ImageFileLoader.LoadedImage;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

/** One owner's generations cover reload/remap/close; worker ownership ends only after actual exit. */
public final class ImageTabController implements EditorTabController {
    private final ImageTabs owner;
    private final ImageTabModel model;
    private final ImageTabView view;
    private LoadedImage loaded;
    private Job pending;
    private boolean disposed;
    private boolean checking;
    ImageTabController(ImageTabs owner, String id, Path path) {
        this.owner = owner; model = new ImageTabModel(id, path); view = new ImageTabView(this, owner.theme());
    }
    @Override public ImageTabModel model() { return model; }
    @Override public TabCloseHandler closeHandler() { return ReadOnlyTabCloseHandler.INSTANCE; }
    public ImageTabView view() { return view; }
    boolean disposed() { return disposed; }
    CompletableFuture<Boolean> requestClose() { return owner.tabs().requestClose(model.id()); }
    public CompletableFuture<Void> reload() {
        if (disposed || owner.paused()) return CompletableFuture.completedFuture(null);
        owner.cancelRestore(this); cancelPending(); clearPixels();
        var before = model.state();
        model.update(new ImageTabState(before.path(), ImageTabState.Phase.LOADING, before.generation() + 1, "", 0, 0, 0, false, "Loading…"));
        changed(); Job job = new Job(before.path(), model.state().generation(), owner.epoch()); pending = job;
        try { owner.decodeExecutor().execute(job); }
        catch (RejectedExecutionException failure) { pending = null; fail("Too many image previews waiting. Retry after current loads finish.", false); job.completion.complete(null); }
        return job.completion;
    }
    public void checkForChanges() {
        if (disposed || owner.paused() || checking || loaded == null) return;
        checking = true; Path path = model.state().path(); long generation = model.state().generation();
        var stamp = observedStamp;
        CompletableFuture.supplyAsync(() -> {
            try { return ImageFileLoader.FileStamp.read(path).equals(stamp); } catch (Exception failure) { return false; }
        }, owner.workers()).whenCompleteAsync((same, failure) -> {
            checking = false;
            if (disposed || generation != model.state().generation() || !path.equals(model.state().path()) || loaded == null) return;
            if (!Boolean.TRUE.equals(same)) {
                var state = model.state(); model.update(new ImageTabState(state.path(), state.phase(), state.generation(), state.format(), state.width(), state.height(), state.encodedBytes(), true, state.message())); changed();
            }
        }, SwingUtilities::invokeLater);
    }
    private ImageFileLoader.FileStamp observedStamp;
    void remap(Path path) {
        if (disposed) return;
        cancelPending(); var before = model.state();
        model.update(new ImageTabState(path, before.phase(), before.generation() + 1, before.format(), before.width(), before.height(), before.encodedBytes(), before.stale(), before.message()));
        reload();
    }
    void missing() { owner.cancelRestore(this); cancelPending(); clearPixels(); fail("Image file is missing. Restore it and Reload to retry.", true); }
    void quiesce() { cancelPending(); }
    private void changed() { view.refreshState(); owner.tabs().refreshTab(model.id()); }
    private void fail(String message, boolean missing) {
        var state = model.state(); model.update(new ImageTabState(state.path(), missing ? ImageTabState.Phase.MISSING : ImageTabState.Phase.FAILED, state.generation(), "", 0, 0, 0, false, message)); changed();
    }
    private void clearPixels() { view.pixels(null); if (loaded != null) { loaded.close(); loaded = null; observedStamp = null; } }
    private void cancelPending() {
        Job job = pending; pending = null;
        if (job != null) { job.canceled.set(true); owner.decodeExecutor().remove(job); job.completion.complete(null); }
    }
    public void dispose() {
        if (disposed) return; disposed = true; cancelPending(); clearPixels(); owner.removed(this);
    }
    private final class Job implements Runnable {
        final AtomicBoolean canceled = new AtomicBoolean();
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final Path path; final long generation; final long epoch;
        Job(Path path, long generation, long epoch) { this.path = path; this.generation = generation; this.epoch = epoch; }
        @Override public void run() {
            LoadedImage result = null; Throwable failure = null;
            try { result = owner.decoder().load(path, canceled::get); } catch (Exception error) { failure = error; }
            LoadedImage image = result; Throwable error = failure;
            SwingUtilities.invokeLater(() -> {
                try {
                    if (disposed || canceled.get() || epoch != owner.epoch() || generation != model.state().generation() || !path.equals(model.state().path())) { if (image != null) image.close(); return; }
                    pending = null;
                    if (error != null) fail(error.getMessage() == null ? "Cannot decode image" : error.getMessage(), error instanceof NoSuchFileException);
                    else {
                        loaded = image; observedStamp = image.stamp();
                        model.update(new ImageTabState(path, ImageTabState.Phase.READY, generation, image.format(), image.pixels().getWidth(), image.pixels().getHeight(), image.stamp().size(), false,
                                "Read-only" + switch (image.format()) {
                                    case "GIF" -> " · First frame only";
                                    case "TIFF", "TIF" -> " · First page only";
                                    case "JPEG", "JPG" -> " · Stored orientation; EXIF rotation is not applied";
                                    default -> "";
                                }));
                        view.pixels(image.pixels()); changed();
                    }
                } finally { completion.complete(null); owner.decodeFinished(); }
            });
        }
    }
}
