package dev.editor.agents.support;

import dev.editor.agents.domain.*;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAgentChatStateStore implements AgentChatStateStore {
    public final ConcurrentHashMap<Path, AgentChatState> states = new ConcurrentHashMap<>();
    public volatile boolean failWrites;
    @Override public AgentChatState load(Path project) { return states.getOrDefault(project, AgentChatState.empty()); }
    @Override public void save(Path project, AgentChatState state) {
        if (failWrites) throw new IllegalStateException("Preferences disk unavailable"); states.put(project, state);
    }
}
