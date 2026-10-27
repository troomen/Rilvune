package dev.editor.git.domain;
import java.io.IOException;
/** Watch events are hints; callers also reconcile periodically while visible. */
public interface RepositoryObservation { AutoCloseable observe(RepositoryLocation repository, Runnable changed) throws IOException; }
