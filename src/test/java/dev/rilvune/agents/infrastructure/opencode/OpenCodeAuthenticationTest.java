package dev.rilvune.agents.infrastructure.opencode;

import com.google.gson.*;
import dev.rilvune.agents.domain.AgentProvider;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.util.*;
import static dev.rilvune.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class OpenCodeAuthenticationTest {
    private static final AgentProvider CODEX = new AgentProvider("openai", "Codex", "openai/", "ChatGPT");

    @Test void connectionStateUsesV2IntegrationsAndRecognizesReauthentication() throws Exception {
        var api = new Api(); var auth = new OpenCodeAuthentication(api, CODEX);
        assertFalse(auth.connected());
        api.connections = List.of(object("type", "env", "name", "OPENAI_API_KEY")); assertTrue(auth.connected());
        api.connections = List.of(object("type", "credential", "status", object("status", "needs_auth"))); assertFalse(auth.connected());
    }
    @Test void browserMethodUsesItsStableIdAndCompletionIsOnlyPolled() throws Exception {
        var api = new Api(); var auth = new OpenCodeAuthentication(api, CODEX);
        assertEquals("https://example.com/authorize", auth.begin().uri().toString());
        assertEquals("chatgpt-token-sharing", api.method);
        auth.awaitCompletion();
        assertEquals(List.of("GET /api/integration", "POST /api/integration/openai/connect/oauth", "GET /api/integration/openai/connect/oauth/con_test"), api.requests);
    }
    @Test void missingBrowserMethodNeverFallsBackToDeviceOrLegacyLogin() {
        var api = new Api(); api.browser = false;
        IOException error = assertThrows(IOException.class, () -> new OpenCodeAuthentication(api, CODEX).begin());
        assertTrue(error.getMessage().contains("Browser sign-in is unavailable")); assertNull(api.method);
    }
    @Test void manualCodeModeIsRejected() {
        var api = new Api(); api.mode = "code";
        assertThrows(IOException.class, () -> new OpenCodeAuthentication(api, CODEX).begin());
    }
    @Test void pendingAttemptWaitsForCompletion() throws Exception {
        var api = new Api(); api.statuses = new ArrayDeque<>(List.of("pending", "complete"));
        var auth = new OpenCodeAuthentication(api, CODEX); auth.begin(); auth.awaitCompletion();
        assertTrue(api.statuses.isEmpty());
    }
    @Test void terminalFailuresAndUnexpectedStatusesCannotUnlockChat() throws Exception {
        for (String status : List.of("expired", "failed", "unknown")) {
            var api = new Api(); api.statuses = new ArrayDeque<>(List.of(status));
            var auth = new OpenCodeAuthentication(api, CODEX); auth.begin();
            assertThrows(IOException.class, auth::awaitCompletion);
        }
    }
    @Test void invalidAuthorizationLinksAreRejected() {
        for (String url : List.of("file:///private", "http://example.com/authorize", "https:relative", "https://user:secret@example.com/")) {
            var api = new Api(); api.url = url;
            assertThrows(IOException.class, () -> new OpenCodeAuthentication(api, CODEX).begin());
        }
    }
    private static final class Api implements OpenCodeApi {
        final List<String> requests = new ArrayList<>();
        List<JsonObject> connections = List.of();
        Deque<String> statuses = new ArrayDeque<>(List.of("complete"));
        String mode = "auto", method, url = "https://example.com/authorize";
        boolean browser = true;
        public JsonElement call(String verb, String path, JsonObject body, java.time.Duration timeout) {
            requests.add(verb + " " + path);
            if (path.equals("/api/integration")) {
                var methods = new ArrayList<>(List.of(
                        object("id", "chatgpt-headless", "type", "oauth", "label", "Device code"),
                        object("id", "chatgpt-browser", "type", "oauth", "label", "Legacy browser")));
                if (browser) methods.add(object("id", "chatgpt-token-sharing", "type", "oauth", "label", "Localized method name"));
                return object("data", List.of(object("id", "openai", "connections", connections, "methods", methods)));
            }
            if (verb.equals("POST") && path.endsWith("/oauth")) {
                method = string(body, "methodID"); return object("data", object("attemptID", "con_test", "url", url, "instructions", "Complete sign-in", "mode", mode));
            }
            if (verb.equals("GET") && path.endsWith("/con_test")) return object("data", object("status", statuses.removeFirst()));
            throw new AssertionError("Unexpected request: " + verb + " " + path);
        }
    }
}
