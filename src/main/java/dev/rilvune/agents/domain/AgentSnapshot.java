package dev.rilvune.agents.domain;

import java.nio.file.Path;
import java.util.List;

/** Immutable presentation state; no Swing, provider wire objects, credentials or child processes. */
public record AgentSnapshot(Path project, Phase phase, String status, AgentEngine engine, AgentProvider provider,
                            AgentSession session, AgentLogin login, boolean cancelling,
                            List<AgentMessage> messages, List<AgentPermission> permissions, AgentChatPage history,
                            String chatTitle, String draft, long draftRevision, String persistenceError, boolean historyTruncated, AgentSessionDetails details,
                            List<AgentAttachment> attachments, boolean importingAttachments) {
    public AgentSnapshot { messages = List.copyOf(messages); permissions = List.copyOf(permissions); attachments = List.copyOf(attachments); }
    public AgentConfiguration configuration() { return session == null ? AgentConfiguration.empty() : session.configuration(); }
    public List<AgentModel> models() { return configuration().models(); }
    public String selectedModel() { return configuration().selectedModel(); }
    public List<AgentEffort> efforts() { return configuration().efforts(); }
    public String selectedEffort() { return configuration().selectedEffort(); }
    public String chatId() { return session == null ? "" : session.id(); }
    public enum Phase { NO_PROJECT, UNSUPPORTED, NOT_INSTALLED, INSTALLING, STARTING, AUTH_REQUIRED, AUTHORIZING, READY, BUSY, REVERTING, STOPPING, ERROR, CLOSED }
}
