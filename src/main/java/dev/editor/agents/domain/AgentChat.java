package dev.editor.agents.domain;

import java.time.Instant;

/** Saved conversation descriptor. Its transcript and agent context belong to the engine. */
public record AgentChat(String id, String title, Instant updatedAt) { }
