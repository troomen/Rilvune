package dev.rilvune.agents.domain;

import java.util.Map;
import java.util.List;

/** Editor preferences only. Never stores a second copy of transcripts, tokens or agent context. */
public record AgentChatState(String selectedChat, Map<String, Preference> preferences) {
    public AgentChatState { preferences = Map.copyOf(preferences); }
    public record Preference(String title, String draft, List<AgentAttachment> attachments) {
        public Preference { attachments = attachments == null ? List.of() : List.copyOf(attachments); }
        public Preference(String title, String draft) { this(title, draft, List.of()); }
    }
    public static AgentChatState empty() { return new AgentChatState("", Map.of()); }
}
