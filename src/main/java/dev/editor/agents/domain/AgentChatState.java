package dev.editor.agents.domain;

import java.util.Map;

/** Editor preferences only. Never stores a second copy of transcripts, tokens or agent context. */
public record AgentChatState(String selectedChat, Map<String, Preference> preferences) {
    public AgentChatState { preferences = Map.copyOf(preferences); }
    public record Preference(String title, String draft) { }
    public static AgentChatState empty() { return new AgentChatState("", Map.of()); }
}
