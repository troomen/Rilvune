package dev.rilvune.agents.domain;

import java.net.URI;

/** Public browser authorization instructions; completion and credentials stay with the runtime. */
public record AgentLogin(URI uri, String instructions) { }
