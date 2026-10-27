package dev.rilvune.agents.domain;

import java.nio.file.Path;
import java.util.function.Consumer;

@FunctionalInterface
public interface AgentRuntimeFactory {
    AgentRuntime create(Path executable, Path project, AgentProvider provider, Consumer<AgentEvent> events);
}
