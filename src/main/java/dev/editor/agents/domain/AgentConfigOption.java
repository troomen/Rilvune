package dev.editor.agents.domain;
import java.util.List;

public record AgentConfigOption(String id, String name, Category category, String value, List<Choice> choices) {
    public enum Category { MODEL, EFFORT, MODE, OTHER }
    public AgentConfigOption { choices = List.copyOf(choices); }
    public record Choice(String id, String name) { }
}
