package dev.rilvune.agents.domain;

import java.nio.file.Path;

/** Blocking profile storage, called on workers. */
public interface AgentChatStateStore {
    AgentChatState load(Path project) throws Exception;
    void save(Path project, AgentChatState state) throws Exception;
}
