package dev.rilvune.agents.domain;

import java.util.List;

public record AgentPermission(String id, String title, String details, List<Option> options, AgentToolCall tool) {
    public AgentPermission(String id, String title, String details, List<Option> options) { this(id, title, details, options, new AgentToolCall(id, title, AgentToolCall.Status.PENDING, details, "")); }
    public AgentPermission { options = List.copyOf(options); }
    public record Option(String id, String name, String kind) { }
}
