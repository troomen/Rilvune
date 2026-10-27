package dev.rilvune.agents.infrastructure.acp;
import com.google.gson.*;
import dev.rilvune.agents.domain.*;
import java.util.*;
import static dev.rilvune.agents.infrastructure.AgentJson.*;

final class AcpQuestionReader {
    private AcpQuestionReader() { }
    static AgentQuestion read(String id, JsonObject request) {
        if (!string(request, "mode").equals("form")) throw new IllegalArgumentException("Unsupported question mode");
        JsonObject schema = child(request, "requestedSchema");
        if (!string(schema, "type").equals("object")) throw new IllegalArgumentException("Unsupported question schema");
        Set<String> required = new HashSet<>(); array(schema, "required").forEach(value -> required.add(value.getAsString()));
        List<AgentQuestion.Field> fields = new ArrayList<>();
        for (var entry : child(schema, "properties").entrySet()) {
            if (fields.size() >= 32) throw new IllegalArgumentException("Too many question fields");
            String key = entry.getKey(); JsonObject value = entry.getValue().getAsJsonObject();
            JsonObject choicesSource = string(value, "type").equals("array") ? child(value, "items") : value;
            List<AgentQuestion.Choice> choices = new ArrayList<>();
            for (String list : List.of("oneOf", "anyOf")) for (JsonElement option : array(choicesSource, list)) {
                JsonObject choice = option.getAsJsonObject(); String choiceId = string(choice, "const"), title = string(choice, "title");
                choices.add(new AgentQuestion.Choice(choiceId, title.isBlank() ? choiceId : title));
            }
            for (JsonElement option : array(choicesSource, "enum")) choices.add(new AgentQuestion.Choice(option.getAsString(), option.getAsString()));
            AgentQuestion.Type type = switch (string(value, "type")) {
                case "string" -> choices.isEmpty() ? AgentQuestion.Type.TEXT : AgentQuestion.Type.CHOICE;
                case "boolean" -> AgentQuestion.Type.BOOLEAN;
                case "number" -> AgentQuestion.Type.NUMBER;
                case "integer" -> AgentQuestion.Type.INTEGER;
                case "array" -> AgentQuestion.Type.MULTIPLE;
                default -> throw new IllegalArgumentException("Unsupported question field type");
            };
            Object initial = value.has("default") ? new Gson().fromJson(value.get("default"), Object.class) : null;
            String title = string(value, "title");
            fields.add(new AgentQuestion.Field(key, title.isBlank() ? key : title, string(value, "description"), type, required.contains(key), choices, initial,
                    integer(value, "minLength"), integer(value, "maxLength"), number(value, "minimum"), number(value, "maximum"),
                    integer(value, "minItems"), integer(value, "maxItems"), string(value, "pattern"), string(value, "format")));
        }
        return new AgentQuestion(id, string(request, "message"), fields);
    }
    private static Integer integer(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : null; }
    private static Double number(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsDouble() : null; }
}
