package dev.editor.platform.text.ui;

import java.nio.file.Path;
import java.util.Optional;

/** A narrow host-supplied live snapshot/edit capability. No ownership or persistence is transferred. */
public interface LiveEditorDocument {
    record Snapshot(String id, Path path, long revision, String text, boolean canRevert, boolean bom, String lineEnding) { }
    Optional<Snapshot> snapshot();
    AutoCloseable observe(Runnable changed);
    boolean replace(long revision, int start, String expected, String replacement);
}
