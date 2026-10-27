package dev.editor.document.domain;

import java.nio.file.Path;
import java.util.UUID;

/** All three versions survive an explicit deferral or application restart. */
public record RecoveryDraft(UUID id, Path path, String text, long revision, TextFormat format,
                            DiskSnapshot base, DiskSnapshot external, SyncState state, String message) { }
