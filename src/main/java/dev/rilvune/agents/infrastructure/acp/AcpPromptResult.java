package dev.rilvune.agents.infrastructure.acp;
import com.google.gson.JsonObject;
import dev.rilvune.agents.domain.AgentUsage;

/** ACP prompt response metadata, independent of the selected harness. */
public final class AcpPromptResult {
    private AcpPromptResult() { }
    public static AgentUsage usage(JsonObject response) { return AcpSessionUpdates.usage(response, true); }
}
