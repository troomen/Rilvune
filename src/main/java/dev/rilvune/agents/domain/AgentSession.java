package dev.rilvune.agents.domain;
import java.util.List;
import java.util.Set;

/** Resumable harness session, its initial catalog and explicitly advertised optional actions. */
public record AgentSession(String id, AgentConfiguration configuration, List<AgentCommand> commands, Set<Capability> capabilities) {
    public enum Capability { FORK }
    public AgentSession { commands = List.copyOf(commands); capabilities = Set.copyOf(capabilities); }
    public AgentSession(String id, AgentConfiguration configuration, List<AgentCommand> commands) { this(id, configuration, commands, Set.of()); }
    public AgentSession(String id, AgentConfiguration configuration) { this(id, configuration, List.of()); }
    public AgentSession withConfiguration(AgentConfiguration value) { return new AgentSession(id, value, commands, capabilities); }
}
