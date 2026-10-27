package dev.rilvune.agents.infrastructure.acp;
import com.google.gson.*;
import dev.rilvune.agents.domain.*;
import java.util.*;
import static dev.rilvune.agents.infrastructure.AgentJson.*;

public final class AcpConfigurationReader {
    private AcpConfigurationReader() { }
    public static List<AgentConfigOption> read(JsonObject response) {
        List<AgentConfigOption> result = new ArrayList<>();
        for (JsonElement element : array(response, "configOptions")) {
            JsonObject option = element.getAsJsonObject();
            if (!string(option, "type").equals("select")) continue;
            List<AgentConfigOption.Choice> choices = new ArrayList<>(); choices(array(option, "options"), choices);
            String id = string(option, "id"), name = string(option, "name");
            result.add(new AgentConfigOption(id, name.isBlank() ? id : name, category(string(option, "category")), string(option, "currentValue"), choices));
        }
        return List.copyOf(result);
    }
    private static AgentConfigOption.Category category(String value) {
        return switch (value) { case "model" -> AgentConfigOption.Category.MODEL; case "thought_level" -> AgentConfigOption.Category.EFFORT; case "mode" -> AgentConfigOption.Category.MODE; default -> AgentConfigOption.Category.OTHER; };
    }
    private static void choices(JsonArray options, List<AgentConfigOption.Choice> result) {
        for (JsonElement item : options) {
            JsonObject choice = item.getAsJsonObject();
            if (choice.has("options")) { choices(array(choice, "options"), result); continue; }
            String id = string(choice, "value"), name = string(choice, "name");
            if (!id.isBlank()) result.add(new AgentConfigOption.Choice(id, name.isBlank() ? id : name));
        }
    }
}
