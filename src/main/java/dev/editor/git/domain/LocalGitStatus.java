package dev.editor.git.domain;

import java.util.List;

/** Disk/index state, independent of the history/reference payload and any window. */
public record LocalGitStatus(String head, String branch, List<GitChange> changes,
                             String fingerprint, String referenceFingerprint) {
    public LocalGitStatus { changes = List.copyOf(changes); }
}
