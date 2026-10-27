package dev.editor.agents.infrastructure;

import com.google.gson.*;

/** Wire-only JSON helpers; JSON values never cross the agent application/domain boundary. */
public final class AgentJson {
    private static final Gson GSON = new Gson();
    private AgentJson() { }
    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) result.add((String)pairs[index], GSON.toJsonTree(pairs[index + 1]));
        return result;
    }
    public static String string(JsonObject object, String name) {
        JsonElement value = object.get(name); return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    public static JsonObject child(JsonObject object, String name) {
        JsonElement value = object.get(name); return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    public static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name); return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
