package dev.rilvune.agents.domain;
import java.util.List;

public record AgentSessionDetails(AgentProgress progress, AgentUsage usage, List<AgentCommand> commands, List<AgentQuestion> questions) {
    public AgentSessionDetails { commands = List.copyOf(commands); questions = List.copyOf(questions); }
}
