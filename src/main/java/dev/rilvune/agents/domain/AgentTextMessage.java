package dev.rilvune.agents.domain;
import dev.rilvune.agents.domain.content.AgentContent;
import java.util.List;
import java.util.ArrayList;

public record AgentTextMessage(long id, Role role, String text, AgentOrigin origin, AgentRevertPoint revertPoint, List<AgentContent> content) implements AgentMessage {
    public AgentTextMessage { content = List.copyOf(content); }
    public AgentTextMessage(long id, Role role, String text, AgentOrigin origin, AgentRevertPoint revertPoint) { this(id, role, text, origin, revertPoint, List.of()); }
    public AgentTextMessage(long id, Role role, String text, AgentOrigin origin) { this(id, role, text, origin, null); }
    public AgentTextMessage(long id, Role role, String text) { this(id, role, text, AgentOrigin.MAIN); }
    public AgentTextMessage withRevertPoint(AgentRevertPoint point) { return new AgentTextMessage(id, role, text, origin, point, content); }
    public AgentTextMessage withContent(AgentContent block) {
        var blocks = new ArrayList<>(content); blocks.add(block); return new AgentTextMessage(id, role, text, origin, revertPoint, blocks);
    }
    @Override public long retainedCharacters() { return AgentMessage.super.retainedCharacters() + content.stream().mapToLong(AgentContent::retainedCharacters).sum(); }
    @Override public String copyText() {
        String attachments = content.stream().map(AgentContent::text).collect(java.util.stream.Collectors.joining("\n\n"));
        return text + (text.isBlank() || attachments.isEmpty() ? "" : "\n\n") + attachments;
    }
}
