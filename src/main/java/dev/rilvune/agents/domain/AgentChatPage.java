package dev.rilvune.agents.domain;

import java.util.List;

public record AgentChatPage(List<AgentChat> chats, String nextCursor) {
    public AgentChatPage { chats = List.copyOf(chats); }
    public static AgentChatPage empty() { return new AgentChatPage(List.of(), ""); }
}
