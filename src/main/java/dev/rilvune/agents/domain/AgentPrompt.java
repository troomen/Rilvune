package dev.rilvune.agents.domain;

import java.util.List;
import java.util.Objects;

/** One user turn, with immutable file snapshots rather than mutable source paths. */
public record AgentPrompt(String text, List<AgentAttachment> attachments) {
    public AgentPrompt {
        Objects.requireNonNull(text); attachments = List.copyOf(attachments);
        if (text.length() > 32_768 || text.isBlank() && attachments.isEmpty()) throw new IllegalArgumentException("Message is empty or too long");
        if (!AgentAttachment.withinLimits(attachments))
            throw new IllegalArgumentException("Attach up to 10 files, totaling at most 20 MiB");
    }
}
