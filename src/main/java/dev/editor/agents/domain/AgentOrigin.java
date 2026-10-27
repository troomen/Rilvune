package dev.editor.agents.domain;

/** Public child-session provenance supplied by the harness. Empty ID means the main conversation. */
public record AgentOrigin(String id, String parentId, int depth, String title) {
    public static final AgentOrigin MAIN = new AgentOrigin("", "", 0, "");
    public boolean child() { return !id.isEmpty(); }
    public long retainedCharacters() { return (long)id.length() + parentId.length() + title.length(); }
    public String label() { return title.isBlank() ? "Agent " + id : title; }
}
