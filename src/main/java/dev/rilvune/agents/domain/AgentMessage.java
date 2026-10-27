package dev.rilvune.agents.domain;

/** Conversation entries retain their meaning; presentation never parses tool state out of prose. */
public sealed interface AgentMessage permits AgentTextMessage, AgentToolMessage, AgentContentMessage, AgentActivityMessage {
    enum Role { USER, ASSISTANT, SYSTEM, TOOL }
    long id();
    Role role();
    String text();
    default long retainedCharacters() { return text().length() + origin().retainedCharacters(); }
    default AgentOrigin origin() { return AgentOrigin.MAIN; }
    default boolean technical() { return this instanceof AgentToolMessage || this instanceof AgentActivityMessage || origin().child(); }
}
