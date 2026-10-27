package dev.editor.agents.infrastructure.opencode;

import com.google.gson.*;
import java.time.Duration;

/** Internal setup transport seam; neither HTTP nor JSON leaves infrastructure. */
@FunctionalInterface
interface OpenCodeApi {
    JsonElement call(String method, String path, JsonObject body, Duration timeout) throws Exception;
}
