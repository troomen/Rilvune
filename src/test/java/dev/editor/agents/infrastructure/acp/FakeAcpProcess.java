package dev.editor.agents.infrastructure.acp;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static dev.editor.agents.infrastructure.AgentJson.*;

/** Isolated subprocess fixture: protocol, permissions and cancellation use real stdin/stdout. */
public final class FakeAcpProcess {
    public static void main(String[] arguments) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            JsonElement pending = null; String line; boolean rich = false;
            while ((line = input.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject(); String method = string(request, "method");
                if (method.equals("initialize")) result(request.get("id"), object("protocolVersion", 1));
                else if (method.equals("session/new")) result(request.get("id"), object("sessionId", "session"));
                else if (method.equals("fixture/catalog")) {
                    send(object("jsonrpc", "2.0", "method", "session/update", "params", object("sessionId", "missing", "update", object("sessionUpdate", "available_commands_update", "availableCommands", java.util.List.of(object("name", "wrong-chat-command")))))); result(request.get("id"), object());
                }
                else if (method.equals("session/load")) send(object("jsonrpc", "2.0", "id", request.get("id"), "error", object("code", -1, "message", "Unknown session")));
                else if (method.equals("fixture/hold")) continue;
                else if (method.equals("fixture/disconnect")) return;
                else if (method.equals("fixture/malformed")) { System.out.println("not-json"); System.out.flush(); }
                else if (method.equals("fixture/update")) {
                    update(object("sessionUpdate", "agent_message_chunk", "content", object("type", "text", "text", "Progress")));
                    result(request.get("id"), object());
                }
                else if (method.equals("fixture/finish")) {
                    if (pending != null) { result(pending, object("stopReason", "end_turn")); pending = null; }
                    result(request.get("id"), object());
                }
                else if (method.equals("session/prompt")) {
                    pending = request.get("id"); rich = string(child(request, "params"), "scenario").equals("rich");
                    if (string(child(request, "params"), "scenario").equals("controlled")) {
                        update(object("sessionUpdate", "agent_message_chunk", "content", object("type", "text", "text", "Started"))); continue;
                    }
                    if (rich) { rich(); continue; }
                    send(object("jsonrpc", "2.0", "method", "session/update", "params", object("sessionId", "session", "update", object("sessionUpdate", "tool_call", "toolCallId", "test", "title", "Project tests", "status", "pending", "rawInput", object("command", "./gradlew test")))));
                    send(object("jsonrpc", "2.0", "method", "session/update", "params", object("sessionId", "session", "update", object("sessionUpdate", "tool_call_update", "toolCallId", "test", "status", "completed", "content", java.util.List.of(object("type", "content", "content", object("type", "text", "text", "All passed")))))));
                    send(object("jsonrpc", "2.0", "method", "session/update", "params", object("sessionId", "session", "update", object("sessionUpdate", "agent_message_chunk", "content", object("type", "text", "text", "Hello")))));
                    send(object("jsonrpc", "2.0", "id", "approval", "method", "session/request_permission", "params", object("sessionId", "session", "toolCall", object("title", "Run tests", "rawInput", object("command", "./gradlew test")), "options", java.util.List.of(object("optionId", "once", "name", "Allow once", "kind", "allow_once")))));
                } else if (method.isEmpty()) {
                    if (rich) update(object("sessionUpdate", "agent_message_chunk", "content", object("type", "text", "text", "Form result: " + child(request, "result"))));
                    String action = string(child(request, "result"), "action");
                    if (pending != null) result(pending, object("stopReason", action.equals("cancel") ? "cancelled" : "end_turn", "usage", object("inputTokens", 12, "outputTokens", 7, "totalTokens", 19))); pending = null;
                }
                else if (method.equals("session/cancel") && pending != null) { result(pending, object("stopReason", "cancelled")); pending = null; }
                else if (request.has("id")) result(request.get("id"), new JsonObject());
            }
        }
    }
    private static void rich() {
        update(object("sessionUpdate", "agent_thought_chunk", "messageId", "thought", "content", object("type", "text", "text", "Planning the next action")));
        update(object("sessionUpdate", "tool_call", "toolCallId", "edit", "title", "Update source", "kind", "edit", "status", "in_progress"));
        update(object("sessionUpdate", "tool_call_update", "toolCallId", "edit", "status", "completed", "content", java.util.List.of(
                object("type", "diff", "path", "/project/Main.java", "oldText", "before", "newText", "after"), object("type", "content", "content", object("type", "text", "text", "Full tool result")))));
        send(object("jsonrpc", "2.0", "method", "opencode/session/child_update", "params", object("rootSessionId", "session", "childSessionId", "child", "parentSessionId", "session", "depth", 1, "title", "Explore",
                "type", "update", "update", object("sessionUpdate", "agent_message_chunk", "messageId", "child-message", "content", object("type", "text", "text", "Child result")))));
        update(object("sessionUpdate", "usage_update", "used", 100, "size", 1000));
        send(object("jsonrpc", "2.0", "id", "question", "method", "elicitation/create", "params", object("mode", "form", "sessionId", "session", "message", "Package manager?",
                "requestedSchema", object("type", "object", "required", java.util.List.of("pm"), "properties", object("pm", object("type", "string", "oneOf", java.util.List.of(object("const", "opaque-bun", "title", "Bun"))))))));
    }
    private static void update(JsonObject value) { send(object("jsonrpc", "2.0", "method", "session/update", "params", object("sessionId", "session", "update", value))); }
    private static void result(JsonElement id, JsonObject result) { send(object("jsonrpc", "2.0", "id", id, "result", result)); }
    private static void send(JsonObject value) { System.out.println(value); System.out.flush(); }
}
