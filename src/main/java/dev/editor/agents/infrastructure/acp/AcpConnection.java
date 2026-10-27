package dev.editor.agents.infrastructure.acp;

import com.google.gson.*;
import dev.editor.agents.domain.*;
import dev.editor.agents.infrastructure.runtime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import static dev.editor.agents.infrastructure.AgentJson.*;

/** Deliberately bounded ACP v1 client subset. Tool execution and file access stay with the harness. */
public final class AcpConnection implements AutoCloseable {
    private final AgentProcess child;
    private final Writer writer;
    private final Consumer<AgentEvent> events;
    private final Map<Long, CompletableFuture<JsonObject>> requests = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> permissions = new ConcurrentHashMap<>();
    // Session changes and reader dispatch share a monitor. ACP updates can omit prior fields.
    private final AcpSessionUpdates updates = new AcpSessionUpdates();
    private final Map<String, PendingQuestion> questions = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, List<AgentCommand>> commands = new LinkedHashMap<>();
    private record PendingQuestion(JsonElement requestId, AgentQuestion question) { }
    @FunctionalInterface private interface ResponseWait { JsonObject await(CompletableFuture<JsonObject> response) throws Exception; }
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed, cancelling;
    private volatile String session = "";
    private Consumer<AgentEvent> replay;

    public AcpConnection(Path executable, Path project, Map<String, String> environment, Consumer<AgentEvent> events) throws IOException {
        this(new AgentProcess(executable, project, List.of("acp"), environment), events);
    }
    public AcpConnection(AgentProcess child, Consumer<AgentEvent> events) {
        this(child, events, new OutputStreamWriter(child.process().getOutputStream(), StandardCharsets.UTF_8));
    }
    AcpConnection(AgentProcess child, Consumer<AgentEvent> events, Writer writer) {
        this.child = child; this.events = events; this.writer = writer;
        Thread.ofVirtual().name("agent-acp-reader").start(this::read);
    }
    public JsonObject request(String method, JsonObject parameters, Duration timeout) throws Exception {
        Objects.requireNonNull(timeout);
        if ("session/prompt".equals(method)) throw new IllegalArgumentException("Use prompt() for turns without a total-duration deadline");
        return exchange(method, parameters, () -> false, response -> response.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    }
    /** A turn can stream or wait for tools/approval indefinitely. Only its terminal response, failure or close ends this wait. */
    public JsonObject prompt(JsonObject parameters, BooleanSupplier cancelled) throws Exception {
        return exchange("session/prompt", parameters, cancelled, CompletableFuture::get);
    }
    private JsonObject exchange(String method, JsonObject parameters, BooleanSupplier cancelled, ResponseWait wait) throws Exception {
        Objects.requireNonNull(cancelled);
        long id = sequence.incrementAndGet(); CompletableFuture<JsonObject> response = new CompletableFuture<>();
        // Registration and failure must be atomic: an untimed request must never miss connection shutdown.
        synchronized (requests) {
            if (closed || requests.size() >= 32) throw new IOException("Agent connection unavailable");
            requests.put(id, response);
        }
        try {
            send(object("jsonrpc", "2.0", "id", id, "method", method, "params", parameters), cancelled);
            return wait.await(response);
        } catch (TimeoutException timeoutFailure) { throw new IOException("OpenCode timed out responding to " + method, timeoutFailure); }
        finally { requests.remove(id); }
    }
    public synchronized void session(String id) { session = id; updates.clear(); if (commands.containsKey(id)) emit(new AgentEvent.Commands(commands.get(id))); }
    public synchronized List<AgentCommand> commands(String id) { return commands.getOrDefault(id, List.of()); }
    public JsonObject replay(String id, JsonObject parameters, Duration timeout, Consumer<AgentEvent> history) throws Exception {
        String previous; Map<String, AgentToolCall> previousTools;
        synchronized (this) { previous = session; previousTools = updates.save(); replay = history; session(id); }
        boolean loaded = false;
        try { JsonObject result = request("session/load", parameters, timeout); loaded = true; return result; }
        finally { synchronized (this) { replay = null; if (!loaded) { session = previous; updates.restore(previousTools); } } }
    }
    private void emit(AgentEvent event) { if (replay != null) replay.accept(event); else events.accept(event); }
    public void cancel() throws IOException {
        List<String> resolved = new ArrayList<>();
        synchronized (writer) {
            cancelling = true;
            for (String id : List.copyOf(permissions.keySet())) completePermission(id, null);
            for (String id : List.copyOf(questions.keySet())) if (completeQuestion(id, new AgentQuestionAnswer(AgentQuestionAnswer.Action.CANCEL, Map.of()))) resolved.add(id);
            send(object("jsonrpc", "2.0", "method", "session/cancel", "params", object("sessionId", session)));
        }
        resolved.forEach(id -> events.accept(new AgentEvent.Resolved(id)));
    }
    public void respondPermission(String id, String option) throws IOException {
        synchronized (writer) { completePermission(id, cancelling ? null : option); }
    }
    private void completePermission(String id, String option) throws IOException {
        JsonElement request = permissions.remove(id); if (request == null) return;
        send(object("jsonrpc", "2.0", "id", request, "result", object("outcome",
                option == null ? object("outcome", "cancelled") : object("outcome", "selected", "optionId", option))));
    }
    private void read() {
        try (Reader reader = new BufferedReader(new InputStreamReader(child.process().getInputStream(), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)))) {
            String line;
            while (!closed && (line = AgentLines.read(reader, 2 * 1024 * 1024)) != null) {
                if (line.isBlank()) continue;
                JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                String method = string(message, "method");
                if (method.isEmpty() && message.has("id")) {
                    CompletableFuture<JsonObject> response = requests.get(message.get("id").getAsLong()); if (response == null) continue;
                    if (message.has("error")) response.completeExceptionally(new IOException("OpenCode: " + string(child(message, "error"), "message")));
                    else response.complete(child(message, "result"));
                } else if (method.equals("session/update")) update(child(message, "params"));
                else if (method.equals("opencode/session/child_update")) childUpdate(child(message, "params"));
                else if (method.equals("$/cancel_request")) resolve(string(child(message, "params"), "requestId"));
                else if (message.has("id")) incoming(message);
            }
            fail(new IOException("OpenCode disconnected"));
        } catch (Exception failure) { fail(new IOException("OpenCode protocol connection failed", failure)); }
    }
    public void answer(String id, AgentQuestionAnswer answer) throws IOException {
        boolean completed;
        synchronized (writer) { completed = completeQuestion(id, cancelling ? new AgentQuestionAnswer(AgentQuestionAnswer.Action.CANCEL, Map.of()) : answer); }
        if (completed) events.accept(new AgentEvent.Resolved(id));
    }
    private boolean completeQuestion(String id, AgentQuestionAnswer answer) throws IOException {
        PendingQuestion pending = questions.get(id); if (pending == null) return false;
        if (answer.action() == AgentQuestionAnswer.Action.ACCEPT) pending.question().validate(answer.values());
        if (!questions.remove(id, pending)) return false;
        send(object("jsonrpc", "2.0", "id", pending.requestId(), "result", object("action", answer.action().name().toLowerCase(Locale.ROOT),
                "content", answer.action() == AgentQuestionAnswer.Action.ACCEPT ? answer.values() : null)));
        return true;
    }
    private void resolve(String id) {
        permissions.remove(id); permissions.remove('"' + id + '"'); questions.remove(id); questions.remove('"' + id + '"');
        events.accept(new AgentEvent.Resolved(id)); events.accept(new AgentEvent.Resolved('"' + id + '"'));
    }
    private synchronized void incoming(JsonObject message) throws IOException {
        JsonObject parameters = child(message, "params"); String method = string(message, "method");
        if (!string(parameters, "sessionId").equals(session)) { unsupported(message); return; }
        JsonElement id = message.get("id"); String key = id.toString();
        if (method.equals("session/request_permission")) {
            if (replay != null || cancelling || permissions.size() >= 8) { send(object("jsonrpc", "2.0", "id", id, "result", object("outcome", object("outcome", "cancelled")))); return; }
            List<AgentPermission.Option> options = new ArrayList<>();
            for (JsonElement element : array(parameters, "options")) {
                JsonObject option = element.getAsJsonObject(); options.add(new AgentPermission.Option(string(option, "optionId"), string(option, "name"), string(option, "kind")));
            }
            permissions.put(key, id); JsonObject supplied = child(parameters, "toolCall");
            AgentToolCall tool = updates.tool(supplied, AcpSessionUpdates.origin(supplied));
            events.accept(new AgentEvent.Permission(new AgentPermission(key, tool.title(), tool.input(), options, tool)));
        } else if (method.equals("elicitation/create")) {
            if (replay != null || cancelling || questions.size() >= 8) { send(object("jsonrpc", "2.0", "id", id, "result", object("action", "cancel"))); return; }
            try {
                AgentQuestion question = AcpQuestionReader.read(key, parameters); questions.put(key, new PendingQuestion(id, question)); events.accept(new AgentEvent.Question(question));
            } catch (IllegalArgumentException unsupported) {
                send(object("jsonrpc", "2.0", "id", id, "result", object("action", "cancel")));
                emit(new AgentEvent.Activity("question:" + key, AgentActivityMessage.Kind.DIAGNOSTIC, "Question could not be displayed", unsupported.getMessage(), AgentToolCall.Status.FAILED, AgentOrigin.MAIN, false));
            }
        } else unsupported(message);
    }
    private void unsupported(JsonObject message) throws IOException { send(object("jsonrpc", "2.0", "id", message.get("id"), "error", object("code", -32601, "message", "Client capability not supported"))); }
    private synchronized void update(JsonObject parameters) {
        JsonObject update = child(parameters, "update"); String id = string(parameters, "sessionId");
        if (string(update, "sessionUpdate").equals("available_commands_update")) {
            for (AgentEvent event : updates.decode(update, replay != null)) if (event instanceof AgentEvent.Commands available) commands.put(id, available.commands());
            if (commands.size() > 32) commands.pollFirstEntry();
        }
        if (!id.equals(session)) return;
        updates.decode(update, replay != null).forEach(this::emit);
    }
    private synchronized void childUpdate(JsonObject parameters) {
        if (!string(parameters, "rootSessionId").equals(session)) return;
        String id = string(parameters, "childSessionId"), title = string(parameters, "title");
        AgentOrigin origin = new AgentOrigin(id, string(parameters, "parentSessionId"), parameters.has("depth") ? parameters.get("depth").getAsInt() : 1, title);
        if (string(parameters, "type").equals("update")) {
            JsonObject update = child(parameters, "update").deepCopy(); JsonObject meta = child(update, "_meta").deepCopy();
            meta.add("opencode/child-session", object("id", id, "parentID", origin.parentId(), "depth", origin.depth(), "title", title)); update.add("_meta", meta);
            updates.decode(update, replay != null).forEach(this::emit);
        } else {
            String status = string(parameters, "status");
            emit(new AgentEvent.Activity("child:" + id, AgentActivityMessage.Kind.CHILD_STATUS, "Agent status", string(child(parameters, "error"), "message"), AcpSessionUpdates.status(status), origin, false));
            emit(new AgentEvent.Progress("child:" + id, new AgentProgress(AgentProgress.Kind.TOOL, origin.label() + " · " + status), origin, status.equals("running") || status.equals("created")));
        }
    }
    private void send(JsonObject message) throws IOException {
        send(message, () -> false);
    }
    private void send(JsonObject message, BooleanSupplier cancelled) throws IOException {
        synchronized (writer) {
            if (closed) throw new IOException("Agent connection closed");
            if (cancelled.getAsBoolean()) throw new CancellationException("Agent prompt cancelled before sending");
            if (string(message, "method").equals("session/prompt")) cancelling = false;
            writer.write(message.toString()); writer.write('\n'); writer.flush();
        }
    }
    private void fail(Exception failure) {
        List<CompletableFuture<JsonObject>> pending;
        synchronized (requests) { closed = true; pending = List.copyOf(requests.values()); requests.clear(); }
        pending.forEach(request -> request.completeExceptionally(failure)); permissions.clear(); questions.clear();
    }
    public boolean alive() { return !closed && child.alive(); }
    @Override public void close() {
        fail(new IOException("Agent connection closed")); child.close();
        // AgentProcess owns/closes the physical stdin pipe. Do not flush the borrowed character writer during
        // teardown: acquiring its monitor or flushing a pending protocol message can block behind an active send.
    }
}
