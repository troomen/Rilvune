package dev.rilvune.settings.domain;

import java.io.IOException;
import java.util.Optional;

/** Revision tokens prevent one instance from overwriting another instance's preferences. */
public interface SettingsStore {
    record Snapshot(EditorSettings settings, String revision, Optional<String> warning) { }

    Snapshot load() throws IOException;

    Snapshot save(EditorSettings settings, String expectedRevision) throws IOException;
}
