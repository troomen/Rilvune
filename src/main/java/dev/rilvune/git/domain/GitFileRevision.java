package dev.rilvune.git.domain;

/** A confirmed tree lookup; absence is distinct from an unreadable/missing object. */
public record GitFileRevision(String blobId, String path, GitContent content) {
    public boolean present() { return !blobId.isEmpty(); }
}
