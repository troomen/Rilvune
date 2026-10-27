package dev.rilvune.agents.infrastructure.opencode;

import com.google.gson.*;
import dev.rilvune.agents.infrastructure.runtime.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Short-lived, password-protected loopback setup API. Chat uses ACP, not this provider-specific API. */
final class OpenCodeControlServer implements OpenCodeApi, AutoCloseable {
    private final AgentProcess child;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String authorization, directory;
    private final CompletableFuture<URI> endpoint = new CompletableFuture<>();
    OpenCodeControlServer(Path executable, Path project, Map<String, String> environment) throws Exception {
        String password = UUID.randomUUID().toString();
        Map<String, String> configured = new HashMap<>(environment); configured.put("OPENCODE_PASSWORD", password);
        authorization = "Basic " + Base64.getEncoder().encodeToString(("opencode:" + password).getBytes(StandardCharsets.UTF_8));
        directory = URLEncoder.encode(project.toString(), StandardCharsets.UTF_8).replace("+", "%20");
        child = new AgentProcess(executable, project, List.of("serve", "--stdio", "--hostname", "127.0.0.1", "--port", "0"), configured);
        Thread.ofVirtual().name("agent-setup-reader").start(() -> {
            try (Reader reader = new BufferedReader(new InputStreamReader(child.process().getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = AgentLines.read(reader, 8192)) != null) {
                    if (line.isBlank()) continue;
                    URI address = URI.create(dev.rilvune.agents.infrastructure.AgentJson.string(JsonParser.parseString(line).getAsJsonObject(), "url"));
                    if (!"http".equals(address.getScheme()) || !"127.0.0.1".equals(address.getHost()) || address.getPort() <= 0)
                        throw new IOException("OpenCode returned an invalid local setup endpoint");
                    endpoint.complete(address);
                }
                endpoint.completeExceptionally(new IOException("OpenCode setup process stopped"));
            } catch (Exception failure) { endpoint.completeExceptionally(failure); }
        });
    }
    @Override public JsonElement call(String method, String path, JsonObject body, Duration timeout) throws Exception {
        String scoped = path + (path.contains("?") ? "&" : "?") + "location%5Bdirectory%5D=" + directory;
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.get(45, TimeUnit.SECONDS).resolve(scoped)).timeout(timeout)
                .header("Authorization", authorization);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("OpenCode setup request failed: " + path + " (HTTP " + response.statusCode() + ")");
            byte[] bytes = stream.readNBytes(16 * 1024 * 1024 + 1); if (bytes.length > 16 * 1024 * 1024) throw new IOException("OpenCode setup response exceeds limit");
            return bytes.length == 0 ? JsonNull.INSTANCE : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        }
    }
    boolean alive() { return child.alive(); }
    @Override public void close() { child.close(); http.shutdownNow(); }
}
