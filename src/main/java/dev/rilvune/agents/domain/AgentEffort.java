package dev.rilvune.agents.domain;

/** Provider-advertised effort; IDs are opaque and no levels are invented by the editor. */
public record AgentEffort(String id, String name) {
    @Override public String toString() { return name; }
}
