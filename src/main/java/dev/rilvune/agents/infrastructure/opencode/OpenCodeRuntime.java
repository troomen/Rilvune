package dev.rilvune.agents.infrastructure.opencode;

import com.google.gson.*;
import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.infrastructure.acp.AcpConnection;
import dev.rilvune.agents.infrastructure.acp.AcpPromptResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import static dev.rilvune.agents.infrastructure.AgentJson.*;

/** OpenCode-specific setup stays behind AgentRuntime; neither the service nor UI speaks OpenCode HTTP. */
public final class OpenCodeRuntime implements AgentRuntime {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private final Path executable, project;
    private final AgentProvider provider;
    private final Consumer<AgentEvent> events;
    private final OpenCodeRuntimePaths paths;
    private Map<String, String> environment = Map.of();
    private OpenCodeAuthentication authentication;
    private volatile OpenCodeControlServer setup;
    private volatile AcpConnection acp;
    private volatile boolean closed, switching;
    private volatile String session = "", modelOption = "model", effortOption = "", recommended = "";
    private Set<AgentSession.Capability> sessionCapabilities = Set.of();
    private volatile AgentConfiguration configuration = AgentConfiguration.empty();

    public OpenCodeRuntime(Path executable, Path project, AgentProvider provider, Consumer<AgentEvent> events, OpenCodeRuntimePaths paths) {
        this.executable = executable; this.project = project; this.provider = provider; this.events = events; this.paths = paths;
    }
    @Override public Setup start() throws Exception {
        Map<String, String> configured = new HashMap<>(paths.environment());
        configured.put("OPENCODE_CONFIG_CONTENT", object("permissions", List.of(
                rule("*", "ask"), rule("read", "allow"), rule("glob", "allow"), rule("grep", "allow"),
                rule("external_directory", "ask"))).toString());
        synchronized (this) {
            checkOpen(); environment = Map.copyOf(configured);
            setup = new OpenCodeControlServer(executable, project, environment);
            authentication = new OpenCodeAuthentication(setup, provider);
        }
        return authentication.connected() ? connect() : new Setup(false, AgentChatPage.empty());
    }
    private static JsonObject rule(String action, String effect) { return object("action", action, "resource", "*", "effect", effect); }
    @Override public AgentLogin beginLogin() throws Exception { checkOpen(); return authentication.begin(); }
    @Override public Setup awaitLogin() throws Exception { checkOpen(); authentication.awaitCompletion(); return connect(); }
    private Setup connect() throws Exception {
        switching = true;
        try {
            OpenCodeControlServer prior = setup;
            recommended = "";
            if (prior != null) {
                JsonObject response = prior.call("GET", "/api/model", null, REQUEST_TIMEOUT).getAsJsonObject();
                for (JsonElement entry : array(response, "data")) {
                    JsonObject model = entry.getAsJsonObject();
                    if (string(model, "providerID").equals(provider.id()) && model.has("enabled") && model.get("enabled").getAsBoolean()) {
                        recommended = provider.modelPrefix() + string(model, "id"); break;
                    }
                }
                prior.close();
            }
            AcpConnection connection;
            synchronized (this) { checkOpen(); setup = null; connection = new AcpConnection(executable, project, environment, this::receive); acp = connection; }
            JsonObject initialized = connection.request("initialize", object("protocolVersion", 1,
                    "clientCapabilities", object("fs", object("readTextFile", false, "writeTextFile", false), "terminal", false, "elicitation", object("form", object()), "session", object("compaction", object()), "_meta", object("opencode/child-session-updates", true)),
                    "clientInfo", object("name", "rilvune", "version", "0.2.0")), REQUEST_TIMEOUT);
            if (!initialized.has("protocolVersion") || initialized.get("protocolVersion").getAsInt() != 1) throw new IOException("Unsupported agent protocol version");
            JsonObject capabilities = child(initialized, "agentCapabilities");
            if (!capabilities.has("loadSession") || !capabilities.get("loadSession").getAsBoolean()
                    || !child(capabilities, "sessionCapabilities").has("list")) throw new IOException("OpenCode does not support saved conversations");
            sessionCapabilities = child(capabilities, "sessionCapabilities").has("fork") ? Set.of(AgentSession.Capability.FORK) : Set.of();
            return new Setup(true, listSessions(""));
        } finally { switching = false; }
    }
    @Override public AgentSession newSession() throws Exception {
        checkOpen();
        if (acp == null) throw new IOException("Connect an account before starting a chat");
        String priorModel = configuration.selectedModel(), priorEffort = configuration.selectedEffort();
        JsonObject created = acp.request("session/new", object("cwd", project.toString(), "mcpServers", List.of()), REQUEST_TIMEOUT);
        String id = string(created, "sessionId"); if (id.isEmpty()) throw new IOException("OpenCode did not create a session");
        session = id; acp.session(session); readConfiguration(created, recommended);
        String choice = !priorModel.isEmpty() && configuration.models().stream().anyMatch(model -> model.id().equals(priorModel)) ? priorModel : configuration.selectedModel();
        selectModel(choice);
        if (!priorEffort.isEmpty() && configuration.selectedModel().equals(priorModel)
                && configuration.efforts().stream().anyMatch(effort -> effort.id().equals(priorEffort))) selectEffort(priorEffort);
        return new AgentSession(session, configuration, acp.commands(session), sessionCapabilities);
    }
    @Override public AgentSession loadSession(String id, Consumer<AgentEvent> history) throws Exception {
        checkOpen();
        String previous = session, previousModelOption = modelOption, previousEffortOption = effortOption;
        AgentConfiguration previousConfiguration = configuration;
        try {
            JsonObject loaded = acp.replay(id, object("sessionId", id, "cwd", project.toString(), "mcpServers", List.of()), Duration.ofMinutes(3), history);
            readConfiguration(loaded, ""); session = id; selectModel(configuration.selectedModel());
            return new AgentSession(session, configuration, acp.commands(session), sessionCapabilities);
        } catch (Exception failure) {
            session = previous; modelOption = previousModelOption; effortOption = previousEffortOption; configuration = previousConfiguration;
            acp.session(previous); throw failure;
        }
    }
    @Override public AgentChatPage listSessions(String cursor) throws Exception {
        checkOpen(); JsonObject parameters = object("cwd", project.toString()); if (!cursor.isBlank()) parameters.addProperty("cursor", cursor);
        JsonObject page = acp.request("session/list", parameters, REQUEST_TIMEOUT); List<AgentChat> chats = new ArrayList<>();
        for (JsonElement item : array(page, "sessions")) {
            JsonObject entry = item.getAsJsonObject(); String id = string(entry, "sessionId");
            String cwd = string(entry, "cwd");
            if (id.isBlank() || cwd.isBlank() || !Path.of(cwd).isAbsolute() || !Path.of(cwd).normalize().equals(project.toAbsolutePath().normalize())) continue;
            java.time.Instant updated;
            try { updated = java.time.Instant.parse(string(entry, "updatedAt")); } catch (java.time.DateTimeException invalid) { updated = java.time.Instant.EPOCH; }
            String title = string(entry, "title"); chats.add(new AgentChat(id, title.isBlank() ? "Untitled chat" : title.substring(0, Math.min(200, title.length())), updated));
        }
        return new AgentChatPage(chats, string(page, "nextCursor"));
    }
    @Override public void deleteSession(String id) throws Exception { checkOpen(); acp.request("session/delete", object("sessionId", id), REQUEST_TIMEOUT); }
    private AgentConfiguration readConfiguration(JsonObject result, String preferred) throws IOException {
        OpenCodeSessionConfiguration parsed = OpenCodeSessionConfiguration.read(result, provider, preferred);
        modelOption = parsed.modelOption(); effortOption = parsed.effortOption(); configuration = parsed.value(); return configuration;
    }
    @Override public AgentConfiguration selectModel(String id) throws Exception {
        checkOpen();
        return readConfiguration(acp.request("session/set_config_option", object("sessionId", session, "configId", modelOption, "value", id), REQUEST_TIMEOUT), id);
    }
    @Override public AgentConfiguration selectEffort(String id) throws Exception {
        checkOpen(); if (effortOption.isBlank()) throw new IOException("This model does not expose effort options");
        return readConfiguration(acp.request("session/set_config_option", object("sessionId", session, "configId", effortOption, "value", id), REQUEST_TIMEOUT), configuration.selectedModel());
    }
    private void receive(AgentEvent event) {
        if (event instanceof AgentEvent.Configuration changed) {
            AgentConfiguration next = AgentConfiguration.from(changed.options(), provider.modelPrefix(), configuration.selectedModel());
            if (!next.models().isEmpty()) configuration = next;
        }
        events.accept(event);
    }
    @Override public AgentConfiguration selectOption(String id, String value) throws Exception {
        checkOpen();
        return readConfiguration(acp.request("session/set_config_option", object("sessionId", session, "configId", id, "value", value), REQUEST_TIMEOUT), configuration.selectedModel());
    }
    @Override public void answer(String id, AgentQuestionAnswer answer) throws Exception { checkOpen(); acp.answer(id, answer); }
    @Override public AgentSession forkSession() throws Exception {
        checkOpen(); if (!sessionCapabilities.contains(AgentSession.Capability.FORK)) throw new IOException("This harness does not support branching chats"); JsonObject forked = acp.request("session/fork", object("sessionId", session, "cwd", project.toString(), "mcpServers", List.of()), REQUEST_TIMEOUT);
        String id = string(forked, "sessionId"); if (id.isBlank()) throw new IOException("OpenCode did not fork the session");
        return new AgentSession(id, readConfiguration(forked, configuration.selectedModel()), acp.commands(id), sessionCapabilities);
    }
    @Override public void prompt(String text, BooleanSupplier cancelled) throws Exception {
        checkOpen();
        try {
            JsonObject result = acp.prompt(object("sessionId", session, "prompt", List.of(object("type", "text", "text", text))), cancelled);
            events.accept(new AgentEvent.Finished(string(result, "stopReason"), AcpPromptResult.usage(result)));
        } catch (CancellationException stoppedBeforeSending) {
            events.accept(new AgentEvent.Finished("not_sent", AgentUsage.empty()));
        }
    }
    @Override public void cancel() throws Exception { AcpConnection current = acp; if (current != null) current.cancel(); }
    @Override public void permission(String id, String option) throws Exception { AcpConnection current = acp; if (current != null) current.respondPermission(id, option); }
    @Override public boolean alive() { return !closed && (switching || acp != null && acp.alive() || setup != null && setup.alive()); }
    private void checkOpen() throws IOException { if (closed) throw new IOException("Agent was stopped"); }
    @Override public void close() {
        synchronized (this) { closed = true; }
        AcpConnection connection = acp; if (connection != null) connection.close();
        OpenCodeControlServer server = setup; if (server != null) server.close();
    }
}
