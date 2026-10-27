package dev.rilvune.document.domain;

import java.util.Objects;

/** A confirmed file revision. Raw text retains original, potentially mixed, line separators. */
public record DiskSnapshot(String text, String rawText, TextFormat format, String fingerprint) {
    public DiskSnapshot {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
