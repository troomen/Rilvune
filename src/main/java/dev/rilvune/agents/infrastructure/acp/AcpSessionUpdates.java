package dev.rilvune.agents.infrastructure.acp;

import com.google.gson.*;
import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.domain.content.AgentContent;
import java.math.BigDecimal;
import java.util.*;
import static dev.rilvune.agents.infrastructure.AgentJson.*;

/** Wire translation and partial-update merging. No UI state or runtime lifecycle responsibilities. */
final class AcpSessionUpdates {
    private final LinkedHashMap<String, AgentToolCall> tools = new LinkedHashMap<>();
    private long notices;
    void clear() { tools.clear(); }
    Map<String, AgentToolCall> save() { return new LinkedHashMap<>(tools); }
    void restore(Map<String, AgentToolCall> saved) { tools.clear(); tools.putAll(saved); }
    List<AgentEvent> decode(JsonObject update, boolean history) {
        List<AgentEvent> events = new ArrayList<>(); AgentOrigin origin = origin(update);
        String type = string(update, "sessionUpdate");
        switch (type) {
            case "agent_message_chunk", "user_message_chunk" -> {
                if (type.equals("user_message_chunk") && !history) break;
                AgentMessage.Role role = type.equals("user_message_chunk") ? AgentMessage.Role.USER : AgentMessage.Role.ASSISTANT;
                AgentContent content = AcpContentReader.block(child(update, "content"));
                if (content instanceof AgentContent.Text text) events.add(new AgentEvent.Text(text.text(), role, string(update, "messageId"), origin));
                else events.add(new AgentEvent.Content(content, role, origin));
            }
            case "agent_thought_chunk" -> {
                String messageId = string(update, "messageId");
                String key = "reasoning:" + origin.id() + ":" + messageId;
                // OpenCode V2 appends a reasoning-part ordinal to the native assistant message ID.
                int part = messageId.indexOf(":reasoning:");
                String source = part < 0 ? messageId : messageId.substring(0, part);
                events.add(new AgentEvent.Activity(key, AgentActivityMessage.Kind.REASONING, "Thinking", AcpContentReader.block(child(update, "content")).text(),
                        history ? AgentToolCall.Status.COMPLETED : AgentToolCall.Status.RUNNING, origin, true, source));
            }
            case "tool_call", "tool_call_update" -> events.add(new AgentEvent.Tool(tool(update, origin)));
            case "plan" -> {
                StringJoiner plan = new StringJoiner("\n");
                for (JsonElement element : array(update, "entries")) {
                    JsonObject entry = element.getAsJsonObject(); plan.add(string(entry, "status") + " · " + string(entry, "content") + " · " + string(entry, "priority"));
                }
                events.add(new AgentEvent.Activity("plan:" + origin.id(), AgentActivityMessage.Kind.PLAN, "Plan", plan.toString(), AgentToolCall.Status.COMPLETED, origin, false));
            }
            case "available_commands_update" -> {
                if (origin.child()) { diagnostic(events, update, origin, "Commands"); break; }
                List<AgentCommand> commands = new ArrayList<>();
                for (JsonElement item : array(update, "availableCommands")) {
                    JsonObject command = item.getAsJsonObject(); commands.add(new AgentCommand(string(command, "name"), string(command, "description"), string(child(command, "input"), "hint")));
                }
                events.add(new AgentEvent.Commands(commands));
            }
            case "config_option_update" -> { if (origin.child()) diagnostic(events, update, origin, "Configuration"); else events.add(new AgentEvent.Configuration(AcpConfigurationReader.read(update))); }
            case "current_mode_update" -> { if (origin.child()) diagnostic(events, update, origin, "Agent mode"); else events.add(new AgentEvent.Mode(string(update, "currentModeId"))); }
            case "usage_update" -> { if (origin.child()) diagnostic(events, update, origin, "Usage"); else events.add(new AgentEvent.Usage(usage(update, false))); }
            case "compaction_update", "compaction_summary_chunk" -> compaction(events, update, origin, type.equals("compaction_summary_chunk"));
            case "session_info_update" -> {
                if (!string(update, "title").isBlank() && !origin.child()) events.add(new AgentEvent.Metadata(string(update, "title")));
                JsonObject meta = child(update, "_meta");
                if (meta.has("opencode/retry")) {
                    JsonObject retry = child(meta, "opencode/retry");
                    String detail = (origin.child() ? origin.label() + " · " : "") + "Retry " + string(retry, "attempt") + " · " + string(child(retry, "error"), "message")
                            + (string(retry, "nextRetryAt").isEmpty() ? "" : " · " + string(retry, "nextRetryAt"));
                    events.add(new AgentEvent.Progress("retry", new AgentProgress(AgentProgress.Kind.RETRYING, detail), origin, !retry.isEmpty()));
                }
                if (meta.has("opencode/compaction")) compaction(events, child(meta, "opencode/compaction"), origin, false);
            }
            default -> events.add(new AgentEvent.Activity("extension:" + ++notices, AgentActivityMessage.Kind.DIAGNOSTIC, "Agent update · " + type, AcpContentReader.display(update), AgentToolCall.Status.COMPLETED, origin, false));
        }
        return events;
    }
    private void diagnostic(List<AgentEvent> events, JsonObject update, AgentOrigin origin, String title) {
        events.add(new AgentEvent.Activity("child-metadata:" + origin.id() + ":" + title, AgentActivityMessage.Kind.DIAGNOSTIC, title, AcpContentReader.display(update), AgentToolCall.Status.COMPLETED, origin, false));
    }
    AgentToolCall tool(JsonObject update, AgentOrigin origin) {
        String id = string(update, "toolCallId"); if (id.isEmpty()) id = "approval";
        if (origin.child() && !id.startsWith(origin.id() + ":")) id = origin.id() + ":" + id;
        AgentToolCall prior = tools.get(id);
        String title = string(update, "title"), kind = string(update, "kind");
        if (title.isEmpty()) title = prior == null ? "Tool activity" : prior.title(); title = AcpContentReader.bounded(title, 1000);
        if (kind.isEmpty()) kind = prior == null ? "other" : prior.kind();
        AgentToolCall.Status status = update.has("status") ? status(string(update, "status")) : prior == null ? AgentToolCall.Status.PENDING : prior.status();
        String input = update.has("rawInput") ? AcpContentReader.display(update.get("rawInput")) : prior == null ? "" : prior.input();
        String output = update.has("rawOutput") ? AcpContentReader.display(update.get("rawOutput")) : prior == null ? "" : prior.output();
        List<AgentContent> content = update.has("content") ? AcpContentReader.tool(update) : prior == null ? List.of() : prior.content();
        List<AgentLocation> locations = new ArrayList<>();
        if (update.has("locations")) for (JsonElement item : array(update, "locations")) {
            JsonObject location = item.getAsJsonObject(); locations.add(new AgentLocation(string(location, "path"), location.has("line") && !location.get("line").isJsonNull() ? location.get("line").getAsInt() : null));
        } else if (prior != null) locations.addAll(prior.locations());
        if (prior != null && !origin.child()) origin = prior.origin();
        AgentToolCall next = new AgentToolCall(id, title, status, input, output, kind, locations, content, origin); tools.put(id, next);
        long retained = tools.values().stream().mapToLong(AgentToolCall::retainedCharacters).sum();
        while (tools.size() > 128 || retained > 4 * 1024 * 1024) { var removed = tools.pollFirstEntry(); if (removed == null) break; retained -= removed.getValue().retainedCharacters(); }
        return next;
    }
    static AgentOrigin origin(JsonObject update) {
        JsonObject child = child(child(update, "_meta"), "opencode/child-session");
        return child.isEmpty() ? AgentOrigin.MAIN : new AgentOrigin(string(child, "id"), string(child, "parentID"), child.has("depth") ? child.get("depth").getAsInt() : 1, string(child, "title"));
    }
    static AgentToolCall.Status status(String status) {
        return switch (status) { case "in_progress", "running", "started" -> AgentToolCall.Status.RUNNING; case "completed" -> AgentToolCall.Status.COMPLETED;
            case "failed" -> AgentToolCall.Status.FAILED; case "cancelled", "interrupted" -> AgentToolCall.Status.CANCELLED; default -> AgentToolCall.Status.PENDING; };
    }
    static AgentUsage usage(JsonObject data, boolean turn) {
        Map<String, Long> tokens = new LinkedHashMap<>();
        if (turn) for (var entry : child(data, "usage").entrySet()) if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) tokens.put(entry.getKey(), entry.getValue().getAsLong());
        JsonObject cost = child(data, "cost");
        return new AgentUsage(data.has("used") ? data.get("used").getAsLong() : 0, data.has("size") ? data.get("size").getAsLong() : 0,
                cost.has("amount") ? cost.get("amount").getAsBigDecimal() : BigDecimal.ZERO, string(cost, "currency"), tokens);
    }
    private static void compaction(List<AgentEvent> events, JsonObject update, AgentOrigin origin, boolean chunk) {
        String id = string(update, "compactionId"); if (id.isBlank()) id = string(update, "messageId");
        String key = "compaction:" + origin.id() + ":" + id; AgentToolCall.Status status = chunk ? AgentToolCall.Status.RUNNING : status(string(update, "status"));
        StringJoiner text = new StringJoiner("\n");
        if (chunk) text.add(AcpContentReader.block(child(update, "content")).text());
        else for (JsonElement item : array(update, "summary")) text.add(AcpContentReader.block(item.getAsJsonObject()).text());
        String error = string(update, "error"); if (error.isEmpty()) error = string(child(update, "error"), "message"); if (!error.isBlank()) text.add(error);
        events.add(new AgentEvent.Activity(key, AgentActivityMessage.Kind.COMPACTION, "Context compaction", text.toString(), status, origin, chunk));
        events.add(new AgentEvent.Progress(key, new AgentProgress(AgentProgress.Kind.COMPACTING, origin.child() ? origin.label() + " · Compacting context…" : "Compacting context…"), origin, status == AgentToolCall.Status.RUNNING));
    }
}
