package dev.editor.agents.infrastructure.opencode;

import com.google.gson.*;
import dev.editor.agents.domain.*;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import static dev.editor.agents.infrastructure.AgentJson.*;

/** V2 integrations own OAuth attempts and token persistence; the editor only displays and polls them. */
final class OpenCodeAuthentication {
    private static final String BROWSER_METHOD = "chatgpt-token-sharing";
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private final OpenCodeApi api;
    private final AgentProvider provider;
    private String attempt = "";

    OpenCodeAuthentication(OpenCodeApi api, AgentProvider provider) { this.api = api; this.provider = provider; }

    private JsonObject integration() throws Exception {
        JsonObject response = api.call("GET", "/api/integration", null, TIMEOUT).getAsJsonObject();
        for (JsonElement entry : array(response, "data")) {
            JsonObject integration = entry.getAsJsonObject();
            if (string(integration, "id").equals(provider.id())) return integration;
        }
        throw new IOException("OpenCode does not expose the " + provider.name() + " integration");
    }

    boolean connected() throws Exception {
        for (JsonElement entry : array(integration(), "connections")) {
            if (!string(child(entry.getAsJsonObject(), "status"), "status").equals("needs_auth")) return true;
        }
        return false;
    }

    AgentLogin begin() throws Exception {
        // Select the supported V2 browser flow by stable ID, never by translated labels or array order.
        // Do not silently fall back to device codes or the legacy Codex OAuth client.
        boolean available = array(integration(), "methods").asList().stream().anyMatch(entry -> {
            JsonObject method = entry.getAsJsonObject();
            return string(method, "id").equals(BROWSER_METHOD)
                    && string(method, "type").equals("oauth") && !method.has("form");
        });
        if (!available) throw new IOException("Browser sign-in is unavailable for " + provider.name() + " in this OpenCode version");
        JsonObject result = child(api.call("POST", base(), object("methodID", BROWSER_METHOD), TIMEOUT).getAsJsonObject(), "data");
        attempt = string(result, "attemptID");
        URI uri = URI.create(string(result, "url"));
        if (attempt.isBlank() || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IOException("OpenCode returned invalid sign-in instructions");
        if (!string(result, "mode").equals("auto")) throw new IOException("OpenCode did not return automatic browser sign-in");
        return new AgentLogin(uri, string(result, "instructions"));
    }

    void awaitCompletion() throws Exception {
        if (attempt.isEmpty()) throw new IOException("Start account sign-in first");
        String path = base() + "/" + attempt;
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonObject result = child(api.call("GET", path, null, TIMEOUT).getAsJsonObject(), "data");
            switch (string(result, "status")) {
                case "complete" -> { return; }
                case "failed" -> throw new IOException("Account sign-in failed. Connect again.");
                case "expired" -> throw new IOException("Account sign-in expired. Connect again.");
                case "pending" -> Thread.sleep(1000);
                default -> throw new IOException("Unknown OpenCode sign-in status");
            }
        }
        throw new IOException("Account sign-in timed out. Connect again.");
    }

    private String base() { return "/api/integration/" + provider.id() + "/connect/oauth"; }
}
