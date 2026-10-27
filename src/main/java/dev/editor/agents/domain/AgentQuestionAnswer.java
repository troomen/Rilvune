package dev.editor.agents.domain;
import java.util.Map;

public record AgentQuestionAnswer(Action action, Map<String, Object> values) {
    public AgentQuestionAnswer { var copy = new java.util.LinkedHashMap<String, Object>(); values.forEach((key, value) -> copy.put(key, value instanceof java.util.List<?> list ? java.util.List.copyOf(list) : value)); values = Map.copyOf(copy); }
    public enum Action { ACCEPT, DECLINE, CANCEL }
}
