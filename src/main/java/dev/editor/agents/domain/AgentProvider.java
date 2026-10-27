package dev.editor.agents.domain;

/** Provider identity belongs to setup; the chat UI does not know provider-specific login protocols. */
public record AgentProvider(String id, String name, String modelPrefix, String accountName) { }
