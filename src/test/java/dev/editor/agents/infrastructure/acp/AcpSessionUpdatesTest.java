package dev.editor.agents.infrastructure.acp;

import com.google.gson.*;
import dev.editor.agents.domain.*;
import dev.editor.agents.domain.content.AgentContent;
import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.editor.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

class AcpSessionUpdatesTest {
    @Test void richToolResultsSurvivePartialUpdatesAndKeepLocationsAndChildIdentity() {
        var reader = new AcpSessionUpdates();
        JsonObject origin = object("opencode/child-session", object("id", "child", "parentID", "main", "depth", 1, "title", "Explore"));
        reader.decode(object("sessionUpdate", "tool_call", "toolCallId", "read", "title", "Read source", "kind", "read", "status", "in_progress",
                "_meta", origin, "locations", List.of(object("path", "/project/Main.java", "line", 4)), "rawInput", object("path", "Main.java")), false);
        var update = (AgentEvent.Tool)reader.decode(object("sessionUpdate", "tool_call_update", "toolCallId", "child:read", "status", "completed",
                "content", List.of(object("type", "content", "content", object("type", "text", "text", "Full result")),
                        object("type", "diff", "path", "/project/Main.java", "oldText", "before", "newText", "after")),
                "rawOutput", object("metadata", object("resultCount", 1))), false).getFirst();
        var tool = update.call(); assertEquals("Read source", tool.title()); assertEquals("read", tool.kind());
        assertEquals(AgentToolCall.Status.COMPLETED, tool.status()); assertEquals("Explore", tool.origin().title());
        assertEquals(4, tool.locations().getFirst().line()); assertTrue(tool.input().contains("Main.java")); assertTrue(tool.output().contains("resultCount"));
        assertEquals("Full result", tool.content().getFirst().text());
        assertEquals(new AgentContent.Diff("/project/Main.java", "before", "after"), tool.content().get(1));
        assertEquals(tool, ((AgentEvent.Tool)reader.decode(object("sessionUpdate", "tool_call_update", "toolCallId", "child:read"), false).getFirst()).call());
    }
    @Test void contentVariantsHaveExplicitRepresentationsInsteadOfDisappearing() {
        assertInstanceOf(AgentContent.Media.class, AcpContentReader.block(object("type", "image", "mimeType", "image/png", "data", "AAAA")));
        assertInstanceOf(AgentContent.Media.class, AcpContentReader.block(object("type", "audio", "mimeType", "audio/wav", "data", "AAAA")));
        assertEquals("Embedded text", AcpContentReader.block(object("type", "resource", "resource", object("uri", "file:///reference", "text", "Embedded text"))).text());
        assertInstanceOf(AgentContent.Resource.class, AcpContentReader.block(object("type", "resource_link", "uri", "https://example.com/reference", "name", "Reference")));
        assertInstanceOf(AgentContent.Terminal.class, AcpContentReader.block(object("type", "terminal", "terminalId", "terminal-1")));
        assertTrue(AcpContentReader.block(object("type", "future-content", "value", "Preserve me")).text().contains("Preserve me"));
    }
    @Test void reasoningPlansRetryCompactionAndUsageAreNotSilentlyIgnored() {
        var reader = new AcpSessionUpdates();
        var thought = (AgentEvent.Activity)reader.decode(object("sessionUpdate", "agent_thought_chunk", "messageId", "reasoning", "content", object("type", "text", "text", "Inspecting the call path")), false).getFirst();
        assertEquals("Inspecting the call path", thought.text()); assertTrue(thought.append());
        assertEquals(AgentToolCall.Status.COMPLETED, ((AgentEvent.Activity)reader.decode(object("sessionUpdate", "agent_thought_chunk", "content", object("type", "text", "text", "Saved reasoning")), true).getFirst()).status());
        var plan = reader.decode(object("sessionUpdate", "plan", "entries", List.of(object("content", "Check tests", "status", "pending", "priority", "high"))), false);
        assertTrue(((AgentEvent.Activity)plan.getFirst()).text().contains("Check tests"));
        var retry = (AgentEvent.Progress)reader.decode(object("sessionUpdate", "session_info_update", "_meta", object("opencode/retry", object("attempt", 2, "error", object("message", "Rate limited")))), false).getFirst();
        assertEquals(AgentProgress.Kind.RETRYING, retry.progress().kind()); assertTrue(retry.progress().detail().contains("Rate limited")); assertTrue(retry.active());
        assertFalse(((AgentEvent.Progress)reader.decode(JsonParser.parseString("{\"sessionUpdate\":\"session_info_update\",\"_meta\":{\"opencode/retry\":null}}").getAsJsonObject(), false).getFirst()).active());
        var compaction = reader.decode(object("sessionUpdate", "compaction_update", "compactionId", "compact", "status", "completed", "summary", List.of(object("type", "text", "text", "Summary"))), false);
        assertEquals("Summary", ((AgentEvent.Activity)compaction.getFirst()).text()); assertFalse(((AgentEvent.Progress)compaction.getLast()).active());
        var usage = ((AgentEvent.Usage)reader.decode(object("sessionUpdate", "usage_update", "used", 400, "size", 1000, "cost", object("amount", 0.01, "currency", "USD")), false).getFirst()).usage();
        assertEquals(400, usage.contextUsed()); assertEquals(1000, usage.contextSize());
        assertEquals(90L, AcpPromptResult.usage(object("usage", object("inputTokens", 90, "cachedReadTokens", 10))).tokens().get("inputTokens"));
    }
    @Test void advertisedCommandsAndAllSelectOptionsRemainDynamic() {
        var reader = new AcpSessionUpdates();
        assertEquals("compact", ((AgentEvent.Commands)reader.decode(object("sessionUpdate", "available_commands_update", "availableCommands", List.of(object("name", "compact", "description", "Compact context"))), false).getFirst()).commands().getFirst().name());
        var options = AcpConfigurationReader.read(object("configOptions", List.of(object("id", "custom-option", "name", "Mode", "category", "mode", "type", "select", "currentValue", "review",
                "options", List.of(object("group", "Agents", "options", List.of(object("value", "review", "name", "Review"))))))));
        assertEquals("custom-option", options.getFirst().id()); assertEquals("Review", options.getFirst().choices().getFirst().name());
        var unknown = reader.decode(object("sessionUpdate", "future-extension", "details", "Visible diagnostic"), false);
        assertTrue(((AgentEvent.Activity)unknown.getFirst()).text().contains("Visible diagnostic"));
    }
    @Test void childTelemetryIsVisibleWithoutReplacingMainChatConfigurationOrUsage() {
        var reader = new AcpSessionUpdates();
        for (String type : List.of("usage_update", "config_option_update", "available_commands_update", "current_mode_update")) {
            var events = reader.decode(object("sessionUpdate", type, "used", 99, "size", 100,
                    "_meta", object("opencode/child-session", object("id", "child", "parentID", "main", "depth", 1, "title", "Explore"))), false);
            assertTrue(events.stream().noneMatch(event -> event instanceof AgentEvent.Usage || event instanceof AgentEvent.Configuration || event instanceof AgentEvent.Commands || event instanceof AgentEvent.Mode));
            var diagnostic = (AgentEvent.Activity)events.getFirst(); assertEquals("child", diagnostic.origin().id()); assertTrue(diagnostic.text().contains(type));
        }
    }
}
