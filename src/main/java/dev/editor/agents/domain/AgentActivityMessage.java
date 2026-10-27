package dev.editor.agents.domain;

/** Structured agent updates. Reasoning appears in the activity trace and as a convenient chat disclosure. */
public record AgentActivityMessage(long id, String key, Kind kind, String title, String text, AgentToolCall.Status status, AgentOrigin origin) implements AgentMessage {
    public enum Kind { REASONING, PLAN, COMPACTION, CHILD_STATUS, DIAGNOSTIC }
    public Role role() { return Role.SYSTEM; }
}
