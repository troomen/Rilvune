package dev.rilvune.agents.domain;

public record AgentModel(String id, String name) {
    @Override public String toString() { return name; }
}
