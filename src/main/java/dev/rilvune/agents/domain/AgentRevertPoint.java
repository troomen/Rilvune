package dev.rilvune.agents.domain;

import java.util.Objects;

/** Opaque native history anchor. Never resolve a conversation boundary by matching prompt text. */
public record AgentRevertPoint(Kind kind, String id) {
    public enum Kind { USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_CALL }
    public AgentRevertPoint {
        Objects.requireNonNull(kind);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing conversation history anchor");
    }
}
