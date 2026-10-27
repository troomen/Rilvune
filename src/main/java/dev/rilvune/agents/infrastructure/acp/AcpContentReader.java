package dev.rilvune.agents.infrastructure.acp;

import com.google.gson.*;
import dev.rilvune.agents.domain.content.AgentContent;
import java.util.*;
import static dev.rilvune.agents.infrastructure.AgentJson.*;

/** Reads every ACP content variant without executing links, fetching resources or interpreting HTML. */
final class AcpContentReader {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private AcpContentReader() { }
    static String display(JsonElement value) { return bounded(PRETTY.toJson(value), 131072); }
    static String bounded(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit) + "\n[Display truncated]"; }
    static List<AgentContent> tool(JsonObject object) {
        List<AgentContent> result = new ArrayList<>();
        for (JsonElement item : array(object, "content")) {
            if (!item.isJsonObject()) { result.add(new AgentContent.Unknown("content", display(item))); continue; }
            JsonObject part = item.getAsJsonObject();
            if (string(part, "type").equals("content")) result.add(block(child(part, "content")));
            else result.add(block(part));
        }
        return List.copyOf(result);
    }
    static AgentContent block(JsonObject block) {
        String type = string(block, "type");
        return switch (type) {
            case "text" -> new AgentContent.Text(bounded(string(block, "text"), 131072));
            case "diff" -> new AgentContent.Diff(string(block, "path"), bounded(string(block, "oldText"), 131072), bounded(string(block, "newText"), 131072));
            case "resource_link" -> new AgentContent.Resource(string(block, "name"), string(block, "uri"), string(block, "mimeType"), string(block, "description"));
            case "resource" -> {
                JsonObject resource = child(block, "resource");
                if (resource.has("text")) yield new AgentContent.Resource(string(block, "name"), string(resource, "uri"), string(resource, "mimeType"), bounded(string(resource, "text"), 131072));
                yield new AgentContent.Media("resource", string(resource, "mimeType"), string(resource, "blob"), string(resource, "uri"));
            }
            case "image", "audio" -> new AgentContent.Media(type, string(block, "mimeType"), string(block, "data"), string(block, "uri"));
            case "terminal" -> new AgentContent.Terminal(string(block, "terminalId"));
            default -> new AgentContent.Unknown(type.isBlank() ? "content" : type, display(block));
        };
    }
}
