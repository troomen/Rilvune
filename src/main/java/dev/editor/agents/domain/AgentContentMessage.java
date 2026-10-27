package dev.editor.agents.domain;
import dev.editor.agents.domain.content.AgentContent;

public record AgentContentMessage(long id, Role role, AgentContent content, AgentOrigin origin) implements AgentMessage {
    public long retainedCharacters() { return content.retainedCharacters() + origin.retainedCharacters(); }
    public String text() { return content.text(); }
}
