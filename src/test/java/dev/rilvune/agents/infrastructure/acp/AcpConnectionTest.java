package dev.rilvune.agents.infrastructure.acp;

import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.infrastructure.runtime.AgentProcess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static dev.rilvune.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AcpConnectionTest {
    @TempDir Path temporary;
    @Test void realSubprocessStreamsAndWaitsForPermissionThenCloses() throws Exception { scenario(false); }
    @Test void cancellationReleasesPendingPermissionWithoutImplicitApproval() throws Exception { scenario(true); }
    @Test void richSubprocessFormsAreExplicitValidatedAndCancelledWithoutImplicitAnswers() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            var process = new AgentProcess(Path.of(System.getProperty("java.home"), "bin", "java"), temporary,
                    List.of("-cp", System.getProperty("java.class.path"), FakeAcpProcess.class.getName()), Map.of());
            try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process, events::add)) {
                connection.session("session"); var prompt = workers.submit(() -> connection.prompt(object("scenario", "rich"), () -> false));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (events.stream().noneMatch(AgentEvent.Question.class::isInstance) && System.nanoTime() < deadline) Thread.sleep(10);
                var question = events.stream().filter(AgentEvent.Question.class::isInstance).map(AgentEvent.Question.class::cast).findFirst().orElseThrow().question();
                assertFalse(prompt.isDone()); assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.origin().id().equals("child")));
                var tool = events.stream().filter(AgentEvent.Tool.class::isInstance).map(AgentEvent.Tool.class::cast).toList().getLast().call();
                assertInstanceOf(dev.rilvune.agents.domain.content.AgentContent.Diff.class, tool.content().getFirst());
                if (cancel) connection.cancel(); else {
                    assertThrows(IllegalArgumentException.class, () -> connection.answer(question.id(), new AgentQuestionAnswer(AgentQuestionAnswer.Action.ACCEPT, Map.of("pm", "unlisted"))));
                    assertFalse(prompt.isDone()); connection.answer(question.id(), new AgentQuestionAnswer(AgentQuestionAnswer.Action.ACCEPT, Map.of("pm", "opaque-bun")));
                }
                var result = prompt.get(5, TimeUnit.SECONDS); assertEquals(cancel ? "cancelled" : "end_turn", string(result, "stopReason"));
                assertEquals(19L, AcpPromptResult.usage(result).tokens().get("totalTokens"));
                assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.text().contains(cancel ? "cancel" : "opaque-bun")));
            }
            assertFalse(process.alive());
        }
    }
    @Test void cancellationWinsOverAnAnswerWaitingForTheProtocolWriter() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        var process = new AgentProcess(Path.of(System.getProperty("java.home"), "bin", "java"), temporary,
                List.of("-cp", System.getProperty("java.class.path"), FakeAcpProcess.class.getName()), Map.of());
        var writer = new java.io.OutputStreamWriter(process.process().getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process, events::add, writer)) {
            connection.session("session"); var prompt = workers.submit(() -> connection.prompt(object("scenario", "rich"), () -> false));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (events.stream().noneMatch(AgentEvent.Question.class::isInstance) && System.nanoTime() < deadline) Thread.sleep(10);
            var question = events.stream().filter(AgentEvent.Question.class::isInstance).map(AgentEvent.Question.class::cast).findFirst().orElseThrow().question();
            var reply = new CompletableFuture<Void>();
            synchronized (writer) {
                Thread thread = Thread.ofPlatform().start(() -> {
                    try { connection.answer(question.id(), new AgentQuestionAnswer(AgentQuestionAnswer.Action.ACCEPT, Map.of("pm", "opaque-bun"))); reply.complete(null); }
                    catch (Exception failure) { reply.completeExceptionally(failure); }
                });
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(Thread.State.BLOCKED, thread.getState()); connection.cancel();
            }
            reply.get(5, TimeUnit.SECONDS); prompt.get(5, TimeUnit.SECONDS);
            connection.request("fixture/fence", object(), Duration.ofSeconds(5));
            var text = events.stream().filter(AgentEvent.Text.class::isInstance).map(AgentEvent.Text.class::cast).map(AgentEvent.Text::text).toList();
            assertTrue(text.stream().anyMatch(value -> value.contains("cancel")));
            assertTrue(text.stream().noneMatch(value -> value.contains("accept")), "A queued answer must never be written after cancellation");
        }
    }
    @Test void failedHistoryLoadDoesNotPublishTheOtherChatsCachedCommands() throws Exception {
        List<AgentEvent> live = new CopyOnWriteArrayList<>(), history = new CopyOnWriteArrayList<>();
        var process = new AgentProcess(Path.of(System.getProperty("java.home"), "bin", "java"), temporary,
                List.of("-cp", System.getProperty("java.class.path"), FakeAcpProcess.class.getName()), Map.of());
        try (var connection = new AcpConnection(process, live::add)) {
            connection.session("session"); connection.request("fixture/catalog", object(), Duration.ofSeconds(5));
            assertFalse(connection.commands("missing").isEmpty());
            assertThrows(Exception.class, () -> connection.replay("missing", object("sessionId", "missing"), Duration.ofSeconds(5), history::add));
            assertTrue(live.stream().noneMatch(AgentEvent.Commands.class::isInstance));
            assertTrue(history.stream().anyMatch(AgentEvent.Commands.class::isInstance));
        }
    }
    private void scenario(boolean cancel) throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        var process = new AgentProcess(Path.of(System.getProperty("java.home"), "bin", "java"), temporary,
                List.of("-cp", System.getProperty("java.class.path"), FakeAcpProcess.class.getName()), Map.of());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process, events::add)) {
            assertEquals(1, connection.request("initialize", object(), Duration.ofSeconds(5)).get("protocolVersion").getAsInt());
            connection.request("session/new", object(), Duration.ofSeconds(5)); connection.session("session");
            assertThrows(CancellationException.class, () -> connection.prompt(object(), () -> true));
            assertTrue(events.isEmpty(), "A cancelled startup must never reach the subprocess");
            var prompt = workers.submit(() -> connection.prompt(object(), () -> false));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (events.stream().noneMatch(AgentEvent.Permission.class::isInstance) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Text text && text.text().equals("Hello"))); assertFalse(prompt.isDone());
            var toolUpdates = events.stream().filter(AgentEvent.Tool.class::isInstance).map(AgentEvent.Tool.class::cast).toList();
            assertEquals(2, toolUpdates.size()); assertEquals("Project tests", toolUpdates.getLast().call().title()); assertEquals(AgentToolCall.Status.COMPLETED, toolUpdates.getLast().call().status());
            assertTrue(toolUpdates.getLast().call().input().contains("./gradlew test"));
            assertEquals("All passed", toolUpdates.getLast().call().content().getFirst().text());
            AgentPermission permission = events.stream().filter(AgentEvent.Permission.class::isInstance).map(AgentEvent.Permission.class::cast).findFirst().orElseThrow().request();
            assertTrue(permission.details().contains("./gradlew test"));
            if (cancel) connection.cancel(); else connection.respondPermission(permission.id(), "once");
            assertTrue(prompt.get(5, TimeUnit.SECONDS).has("stopReason"));
        }
        assertFalse(process.alive());
    }
}
