package dev.editor.agents.domain;

public record AgentToolMessage(long id, AgentToolCall call) implements AgentMessage {
    @Override public long retainedCharacters() { return call.retainedCharacters(); }
    @Override public AgentOrigin origin() { return call.origin(); }
    @Override public Role role() { return Role.TOOL; }
    @Override public String text() { return call.title() + "\n" + call.details(); }
}
