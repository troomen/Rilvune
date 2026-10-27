package dev.editor.session.domain;

import java.io.IOException;
import java.nio.file.Path;

/** Blocking storage used by project lifecycle; Swing state has already been captured. */
public interface SessionStore {
    SessionState load(Path workspace) throws IOException;
    void save(SessionState state) throws IOException;
    void remember(Path workspace) throws IOException;
}
