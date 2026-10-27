package dev.rilvune.agents.domain;

/** A displayable activity, distinct from the runtime lifecycle and from conversation content. */
public record AgentProgress(Kind kind, String detail) {
    public enum Kind { IDLE, WAITING, THINKING, RESPONDING, TOOL, RETRYING, COMPACTING, APPROVAL, QUESTION, STOPPING, STOPPED, ERROR }
    public static AgentProgress idle() { return new AgentProgress(Kind.IDLE, "Ready"); }
    public boolean animated() { return switch (kind) { case WAITING, THINKING, RESPONDING, TOOL, COMPACTING -> true; default -> false; }; }
}
