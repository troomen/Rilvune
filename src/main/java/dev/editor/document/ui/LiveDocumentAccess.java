package dev.editor.document.ui;

import dev.editor.document.domain.SyncState;
import dev.editor.platform.text.ui.LiveEditorDocument;
import java.util.Optional;

/** Adapts existing document revision checks and Undo; it never adds a lease or writes directly to disk. */
final class LiveDocumentAccess implements LiveEditorDocument {
    private final DocumentBinding binding;
    LiveDocumentAccess(DocumentBinding binding) { this.binding = binding; }
    @Override public Optional<Snapshot> snapshot() {
        if (binding.session().isClosed() || binding.session().isClosing()) return Optional.empty();
        return binding.currentSnapshot().map(value -> new Snapshot(value.id().toString(), value.path(), value.revision(), value.text(),
                binding.editable() && (value.state() == SyncState.SYNCHRONIZED || value.state() == SyncState.SYNCHRONIZING), value.format().byteOrderMark(), value.format().lineEnding().name()));
    }
    @Override public AutoCloseable observe(Runnable changed) { return binding.observe(ignored -> changed.run()); }
    @Override public boolean replace(long revision, int start, String expected, String replacement) {
        Optional<Snapshot> current = snapshot();
        if (current.isEmpty() || !current.get().canRevert() || current.get().revision() != revision || start < 0
                || start + expected.length() > current.get().text().length() || !current.get().text().regionMatches(start, expected, 0, expected.length())) return false;
        return binding.replaceIfCurrent(revision, start, expected.length(), replacement);
    }
}
