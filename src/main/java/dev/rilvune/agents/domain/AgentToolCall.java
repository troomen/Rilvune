package dev.rilvune.agents.domain;
import dev.rilvune.agents.domain.content.AgentContent;
import java.util.List;

/** Tool state, rich results and child provenance remain typed through the presentation boundary. */
public record AgentToolCall(String id, String title, Status status, String input, String output,
                            String kind, List<AgentLocation> locations, List<AgentContent> content, AgentOrigin origin) {
    public AgentToolCall { locations = List.copyOf(locations); content = List.copyOf(content); }
    public AgentToolCall(String id, String title, Status status, String input, String output) {
        this(id, title, status, input, output, "other", List.of(), List.of(), AgentOrigin.MAIN);
    }
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
    public AgentToolCall withStatus(Status status) { return new AgentToolCall(id, title, status, input, output, kind, locations, content, origin); }
    public long retainedCharacters() {
        return (long)id.length() + title.length() + input.length() + output.length() + kind.length() + origin.retainedCharacters()
                + locations.stream().mapToLong(location -> location.path().length()).sum() + content.stream().mapToLong(AgentContent::retainedCharacters).sum();
    }
    public String details() {
        String rich = content.stream().map(AgentContent::text).collect(java.util.stream.Collectors.joining("\n\n"));
        return (input.isBlank() ? "" : "Input\n" + input + "\n\n") + (rich.isBlank() ? output : rich + (output.isBlank() ? "" : "\n\nMetadata\n" + output));
    }
}
