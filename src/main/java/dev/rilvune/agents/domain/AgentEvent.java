package dev.rilvune.agents.domain;
import dev.rilvune.agents.domain.content.AgentContent;
import java.util.List;

public sealed interface AgentEvent {
    record Text(String text, AgentMessage.Role role, String messageId, AgentOrigin origin) implements AgentEvent {
        public Text(String text) { this(text, AgentMessage.Role.ASSISTANT, "", AgentOrigin.MAIN); }
        public Text(String text, AgentMessage.Role role, String messageId) { this(text, role, messageId, AgentOrigin.MAIN); }
    }
    record Content(AgentContent content, AgentMessage.Role role, AgentOrigin origin) implements AgentEvent { }
    record Activity(String key, AgentActivityMessage.Kind kind, String title, String text, AgentToolCall.Status status, AgentOrigin origin, boolean append, String messageId) implements AgentEvent {
        public Activity(String key, AgentActivityMessage.Kind kind, String title, String text, AgentToolCall.Status status, AgentOrigin origin, boolean append) {
            this(key, kind, title, text, status, origin, append, "");
        }
    }
    record Tool(AgentToolCall call) implements AgentEvent { }
    record Permission(AgentPermission request) implements AgentEvent { }
    record Question(AgentQuestion question) implements AgentEvent { }
    record Resolved(String id) implements AgentEvent { }
    record Progress(String key, AgentProgress progress, AgentOrigin origin, boolean active) implements AgentEvent { }
    record Usage(AgentUsage usage) implements AgentEvent { }
    record Commands(List<AgentCommand> commands) implements AgentEvent { public Commands { commands = List.copyOf(commands); } }
    record Configuration(List<AgentConfigOption> options) implements AgentEvent { public Configuration { options = List.copyOf(options); } }
    record Mode(String id) implements AgentEvent { }
    record Metadata(String title) implements AgentEvent { }
    record Finished(String reason, AgentUsage usage) implements AgentEvent { }
}
