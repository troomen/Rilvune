package dev.editor.agents.infrastructure.opencode;

import dev.editor.agents.domain.*;
import dev.editor.agents.infrastructure.acp.AcpConnection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import static dev.editor.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native integration: private editor profile, no real credentials or provider inference. */
@EnabledIfEnvironmentVariable(named = "EDITOR_AGENT_SMOKE", matches = "true")
class OpenCodeSmokeTest {
    @TempDir Path project;
    private final OpenCodeRuntimePaths paths = new OpenCodeRuntimePaths(Path.of(".cache/opencode-smoke-v2/profile"));
    private static final AgentProvider CODEX = new AgentProvider("openai", "Codex", "openai/", "ChatGPT");
    private Path install() throws Exception { return new OpenCodeInstaller(paths.bin()).install(ignored -> { }); }

    @Test void pinnedBinaryStartsV2SetupAndAcpWithoutGit() throws Exception {
        Path binary = install(); assertEquals(binary, new OpenCodeInstaller(paths.bin()).installed().orElseThrow());
        assertTrue(binary.startsWith(paths.bin().resolve("2.1.3")));
        try (var setup = new OpenCodeControlServer(binary, project, paths.environment())) {
            var integrations = setup.call("GET", "/api/integration", null, Duration.ofSeconds(60)).getAsJsonObject();
            assertEquals(project.toString(), child(integrations, "location").get("directory").getAsString());
            assertTrue(array(integrations, "data").asList().stream().anyMatch(entry -> string(entry.getAsJsonObject(), "id").equals("openai")));
            var info = setup.call("GET", "/api/info", null, Duration.ofSeconds(30)).getAsJsonObject();
            assertTrue(info.has("paths"));
        }
        try (var connection = new AcpConnection(binary, project, paths.environment(), ignored -> { })) {
            var result = connection.request("initialize", object("protocolVersion", 1, "clientCapabilities", object()), Duration.ofSeconds(60));
            assertEquals(1, result.get("protocolVersion").getAsInt());
            var session = connection.request("session/new", object("cwd", project.toString(), "mcpServers", List.of()), Duration.ofSeconds(60));
            assertFalse(string(session, "sessionId").isEmpty()); assertTrue(session.has("configOptions"));
        }
        assertFalse(Files.exists(project.resolve(".git")));
        assertTrue(Files.exists(paths.data().resolve("opencode/opencode.db")));
    }
    @Test @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "editor-smoke-placeholder")
    void nativeRuntimeSelectsProviderModelsWithoutSendingInferenceRequests() throws Exception {
        try (var runtime = new OpenCodeRuntime(install(), project, CODEX, ignored -> { }, paths)) {
            assertTrue(runtime.start().authenticated());
            var first = runtime.newSession(); var configuration = first.configuration(); assertFalse(configuration.models().isEmpty());
            assertTrue(configuration.models().stream().anyMatch(model -> model.id().equals(configuration.selectedModel()))); assertTrue(runtime.alive());
            String effort = configuration.efforts().stream().filter(option -> !option.id().equals("default")).findFirst().map(AgentEffort::id).orElse("");
            if (!effort.isEmpty()) assertEquals(effort, runtime.selectEffort(effort).selectedEffort());
            var fresh = runtime.newSession();
            assertTrue(first.capabilities().contains(AgentSession.Capability.FORK));
            assertTrue(first.commands().stream().anyMatch(command -> command.name().equals("compact")));
            assertNotEquals(first.id(), fresh.id()); assertEquals(configuration.selectedModel(), fresh.configuration().selectedModel());
            if (!effort.isEmpty()) assertEquals(effort, fresh.configuration().selectedEffort());
            assertTrue(runtime.listSessions("").chats().stream().anyMatch(chat -> chat.id().equals(first.id())));
            var loaded = runtime.loadSession(first.id(), ignored -> { }); assertEquals(first.id(), loaded.id());
            if (!effort.isEmpty()) assertEquals(effort, loaded.configuration().selectedEffort());
            var mode = loaded.configuration().options().stream().filter(option -> option.category() == AgentConfigOption.Category.MODE).findFirst().orElseThrow();
            var chosenMode = mode.choices().getLast();
            assertTrue(runtime.selectOption(mode.id(), chosenMode.id()).options().stream().anyMatch(option -> option.id().equals(mode.id()) && option.value().equals(chosenMode.id())));
            var forked = runtime.forkSession(); assertNotEquals(first.id(), forked.id());
            var branched = runtime.loadSession(forked.id(), ignored -> { });
            assertTrue(branched.configuration().options().stream().anyMatch(option -> option.id().equals(mode.id()) && option.value().equals(chosenMode.id())));
            runtime.deleteSession(forked.id());
            runtime.deleteSession(fresh.id());
            assertFalse(runtime.listSessions("").chats().stream().anyMatch(chat -> chat.id().equals(fresh.id())));
        }
    }
    @Test @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "editor-smoke-placeholder")
    void nativePromptStreamsAndWaitsForExplicitToolPermission() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CompletableFuture<AgentPermission> approval = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger(); AtomicBoolean toolResult = new AtomicBoolean();
        AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();
        HttpServer provider = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            try {
                if (!exchange.getRequestURI().getPath().equals("/v1/responses"))
                    throw new java.io.IOException("Unexpected native provider path: " + exchange.getRequestURI());
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var input = com.google.gson.JsonParser.parseString(request).getAsJsonObject();
                // OpenCode may make a separate title-generation request without agent tools.
                int turn = array(input, "tools").isEmpty() ? 0 : calls.incrementAndGet();
                String body;
                if (turn == 1) {
                    String tool = array(input, "tools").asList().stream().map(com.google.gson.JsonElement::getAsJsonObject)
                            .map(item -> string(item, "name")).filter(name -> name.equals("shell")).findFirst().orElseThrow();
                    var arguments = object("command", "printf fixture-executed-through-native-tool | tee native-created.txt", "description", "Create native test fixture");
                    var item = object("id", "fc_fixture", "type", "function_call", "call_id", "fixture_shell", "name", tool, "arguments", arguments.toString(), "status", "completed");
                    body = frame(object("type", "response.output_item.added", "output_index", 0,
                            "item", object("id", "fc_fixture", "type", "function_call", "call_id", "fixture_shell", "name", tool, "arguments", "")))
                            + frame(object("type", "response.function_call_arguments.done", "item_id", "fc_fixture", "arguments", arguments.toString()))
                            + frame(object("type", "response.output_item.done", "output_index", 0, "item", item)) + completed(item);
                } else {
                    if (request.contains("fixture-executed-through-native-tool")) toolResult.set(true);
                    var item = object("id", "msg_fixture", "type", "message", "role", "assistant", "status", "completed",
                            "content", List.of(object("type", "output_text", "text", "Native fixture reply", "annotations", List.of())));
                    body = frame(object("type", "response.output_item.added", "output_index", 0, "item", object("id", "msg_fixture", "type", "message", "role", "assistant", "content", List.of())))
                            + frame(object("type", "response.output_text.delta", "item_id", "msg_fixture", "output_index", 0, "content_index", 0, "delta", "Native fixture reply"))
                            + frame(object("type", "response.output_item.done", "output_index", 0, "item", item)) + completed(item);
                }
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) { fixtureFailure.set(failure); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        provider.start();
        Path config = paths.config().resolve("opencode.json");
        try {
            paths.environment();
            Files.writeString(config, object("providers", object("openai", object("settings", object("baseURL",
                    "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1", "transport", "http")))).toString());
            try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                 var runtime = new OpenCodeRuntime(install(), project, CODEX, event -> {
                     events.add(event); if (event instanceof AgentEvent.Permission permission) approval.complete(permission.request());
                 }, paths)) {
                assertTrue(runtime.start().authenticated()); AgentSession original = runtime.newSession(); runtime.selectModel("openai/gpt-4o-mini");
                var prompt = CompletableFuture.runAsync(() -> {
                    try { runtime.prompt("Create the fixture", () -> false); }
                    catch (Exception failure) { throw new CompletionException(failure); }
                }, workers);
                prompt.whenComplete((ignored, failure) -> {
                    if (failure != null) approval.completeExceptionally(failure);
                    else if (!approval.isDone()) approval.completeExceptionally(new AssertionError("Native prompt completed without permission"));
                });
                AgentPermission permission;
                try { permission = approval.get(30, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Native permission missing; fixture failure=" + fixtureFailure.get() + "; events=" + events, failure); }
                assertFalse(prompt.isDone());
                runtime.permission(permission.id(), permission.options().stream().filter(option -> option.kind().equals("allow_once")).findFirst().orElseThrow().id());
                prompt.get(30, TimeUnit.SECONDS);
                assertNull(fixtureFailure.get()); assertEquals(2, calls.get()); assertTrue(toolResult.get());
                assertEquals("fixture-executed-through-native-tool", Files.readString(project.resolve("native-created.txt")));
                assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.text().contains("Native fixture reply")));
                assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Tool tool && tool.call().status() == AgentToolCall.Status.COMPLETED));
                runtime.newSession(); List<AgentEvent> history = new ArrayList<>();
                assertEquals(original.id(), runtime.loadSession(original.id(), history::add).id());
                assertTrue(history.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.role() == AgentMessage.Role.USER && text.text().contains("Create the fixture")));
                assertTrue(history.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.role() == AgentMessage.Role.ASSISTANT && text.text().contains("Native fixture reply")));
                assertEquals(2, calls.get(), "Loading saved context must never repeat inference");
                try (var reopened = new OpenCodeRuntime(install(), project, CODEX, ignored -> { }, paths)) {
                    assertTrue(reopened.start().chats().chats().stream().anyMatch(chat -> chat.id().equals(original.id())));
                    List<AgentEvent> restored = new ArrayList<>(); reopened.loadSession(original.id(), restored::add);
                    assertTrue(restored.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.text().contains("Native fixture reply")));
                    assertEquals(2, calls.get(), "A new runtime must restore history without inference");
                }
            }
        } finally { provider.stop(0); Files.deleteIfExists(config); }
    }
    private static String completed(com.google.gson.JsonObject item) {
        return frame(object("type", "response.completed", "response", object("id", "resp_fixture", "status", "completed", "output", List.of(item),
                "usage", object("input_tokens", 10, "output_tokens", 10, "total_tokens", 20))));
    }
    private static String frame(Object value) { return "data: " + value + "\n\n"; }
    @Test @EnabledIfEnvironmentVariable(named = "EDITOR_AGENT_AUTH_SMOKE", matches = "true")
    void browserAuthorizationOwnsALoopbackCallbackAndRejectsInvalidState() throws Exception {
        URI callback;
        try (var http = HttpClient.newHttpClient();
             var setup = new OpenCodeControlServer(install(), project, paths.environment())) {
            var auth = new OpenCodeAuthentication(setup, CODEX);
            AgentLogin login = auth.begin();
            assertEquals("https", login.uri().getScheme()); assertFalse(login.instructions().isBlank());
            Map<String, String> query = new HashMap<>();
            for (String item : login.uri().getRawQuery().split("&")) {
                String[] pair = item.split("=", 2);
                query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertEquals("S256", query.get("code_challenge_method"));
            assertTrue(query.get("scope").contains("chatgpt.tokens.use.direct"));
            callback = URI.create(query.get("redirect_uri"));
            assertEquals("http", callback.getScheme()); assertEquals("127.0.0.1", callback.getHost());
            assertEquals("/auth/callback", callback.getPath()); assertTrue(callback.getPort() > 0);
            var unknown = HttpRequest.newBuilder(callback.resolve("/not-a-callback")).timeout(Duration.ofSeconds(5)).GET().build();
            assertEquals(404, http.send(unknown, HttpResponse.BodyHandlers.discarding()).statusCode());
            // Exercise the real listener, without opening a browser or requesting/exchanging credentials.
            var invalid = HttpRequest.newBuilder(URI.create(callback + "?code=fixture&state=invalid"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            assertEquals(400, http.send(invalid, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertThrows(IOException.class, auth::awaitCompletion);
        }
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(callback).timeout(Duration.ofSeconds(5)).GET().build();
            assertThrows(IOException.class, () -> http.send(request, HttpResponse.BodyHandlers.discarding()));
        }
    }
}
