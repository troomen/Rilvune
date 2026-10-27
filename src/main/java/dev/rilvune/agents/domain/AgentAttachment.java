package dev.rilvune.agents.domain;

import java.util.Objects;

/** Immutable reference to a private, content-addressed attachment snapshot. Never contains inline media. */
public record AgentAttachment(String id, String name, String mimeType, long size, String sourceUri) {
    public static final int MAX_COUNT = 10;
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public AgentAttachment {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(mimeType);
        sourceUri = sourceUri == null ? "" : sourceUri;
        if (sourceUri.length() > 32768 || !sourceUri.isEmpty() && !"file".equals(java.net.URI.create(sourceUri).getScheme()))
            throw new IllegalArgumentException("Invalid attachment source");
        if (!id.matches("[a-f0-9]{64}\\.(png|jpg|gif|webp|pdf|txt)") || name.isBlank() || name.length() > 255
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 || size < 0 || size > MAX_BYTES)
            throw new IllegalArgumentException("Invalid attachment");
        if (!mimeType.matches("image/(png|jpeg|gif|webp)|application/pdf|text/plain"))
            throw new IllegalArgumentException("Unsupported attachment format");
    }
    public AgentAttachment(String id, String name, String mimeType, long size) { this(id, name, mimeType, size, ""); }
    public boolean image() { return mimeType.startsWith("image/"); }
    /** A snapshot can be attached under different meaningful filenames while sharing the same stored bytes. */
    public String key() { return id + ":" + name.length() + ":" + name + ":" + sourceUri; }
    public static boolean withinLimits(java.util.List<AgentAttachment> attachments) {
        return attachments.size() <= MAX_COUNT && attachments.stream().mapToLong(AgentAttachment::size).sum() <= MAX_BYTES;
    }
}
