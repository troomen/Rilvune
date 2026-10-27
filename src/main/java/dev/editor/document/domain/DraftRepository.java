package dev.editor.document.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface DraftRepository {
    record Problem(Path file, String message) {
        public Problem { if (message == null || message.isBlank()) message = "Recovery operation failed"; }
        public String description() { return file == null ? message : file + ": " + message; }
    }
    record Scan(List<RecoveryDraft> drafts, List<Problem> problems) {
        public Scan { drafts = List.copyOf(drafts); problems = List.copyOf(problems); }
    }

    /** Exclusive lifetime ownership; repositories without shared storage may use the default. */
    default boolean claim(UUID id) throws IOException { return true; }
    default void release(UUID id) throws IOException { }
    void save(RecoveryDraft draft) throws IOException;
    /** Healthy records only; interactive callers use scan() to retain diagnostics. */
    List<RecoveryDraft> load() throws IOException;
    default Scan scan() throws IOException { return new Scan(load(), List.of()); }
    /** Read the current record after ownership has been acquired, without trusting a prior scan. */
    default java.util.Optional<RecoveryDraft> find(UUID id) throws IOException {
        return load().stream().filter(draft -> draft.id().equals(id)).findFirst();
    }
    void remove(UUID id) throws IOException;
}
