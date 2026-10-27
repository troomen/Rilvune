package dev.rilvune.agents.domain;
public record AgentTextMessage(long id, Role role, String text, AgentOrigin origin, AgentRevertPoint revertPoint) implements AgentMessage {
    public AgentTextMessage(long id, Role role, String text, AgentOrigin origin) { this(id, role, text, origin, null); }
    public AgentTextMessage(long id, Role role, String text) { this(id, role, text, AgentOrigin.MAIN); }
    public AgentTextMessage withRevertPoint(AgentRevertPoint point) { return new AgentTextMessage(id, role, text, origin, point); }
}
