package dev.rilvune.agents.domain;
import java.math.BigDecimal;
import java.util.Map;

/** Context usage and session cost are separate from the token totals of the last turn. */
public record AgentUsage(long contextUsed, long contextSize, BigDecimal cost, String currency, Map<String, Long> tokens) {
    public AgentUsage { tokens = Map.copyOf(tokens); }
    public static AgentUsage empty() { return new AgentUsage(0, 0, BigDecimal.ZERO, "", Map.of()); }
    public AgentUsage merge(AgentUsage update) {
        return new AgentUsage(update.contextSize > 0 ? update.contextUsed : contextUsed,
                update.contextSize > 0 ? update.contextSize : contextSize,
                update.currency.isEmpty() ? cost : update.cost, update.currency.isEmpty() ? currency : update.currency,
                update.tokens.isEmpty() ? tokens : update.tokens);
    }
}
