package dev.rilvune.agents.infrastructure.acp;

import dev.rilvune.agents.domain.AgentEvent;
import dev.rilvune.agents.infrastructure.runtime.AgentProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static dev.rilvune.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real protocol pipes, deterministic completion gates, and short test-only deadlines; no inference/network. */
@Timeout(15)
class AcpPromptLifecycleTest {
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(5);
    @TempDir Path temporary;

    @Test void timedControlRequestsCannotAccidentallyReintroduceATotalPromptDeadline() throws Exception {
        try (var connection = new AcpConnection(process(), ignored -> { })) {
            initialize(connection);
            assertThrows(IllegalArgumentException.class, () -> connection.request("session/prompt", object(), Duration.ofMinutes(20)));
            assertTrue(connection.alive()); connection.request("fixture/fence", object(), CONTROL_TIMEOUT);
        }
    }

    @Test void promptSurvivesStreamingAndShortControlTimeoutsUntilItsActualTerminalResponse() throws Exception {
        var events = new LinkedBlockingQueue<AgentEvent>(); var process = process();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process, events::add)) {
            initialize(connection);
            var prompt = workers.submit(() -> connection.prompt(object("scenario", "controlled"), () -> false));
            awaitStarted(events);
            for (int i = 0; i < 4; i++) {
                connection.request("fixture/update", object(), CONTROL_TIMEOUT);
                assertTrue(events.poll(5, TimeUnit.SECONDS) instanceof AgentEvent.Text);
                IOException timeout = assertThrows(IOException.class,
                        () -> connection.request("fixture/hold", object(), Duration.ofMillis(50)));
                assertInstanceOf(TimeoutException.class, timeout.getCause());
                assertFalse(prompt.isDone(), "A turn must remain pending until its own terminal response"); assertTrue(connection.alive());
            }
            connection.request("fixture/finish", object(), CONTROL_TIMEOUT);
            assertEquals("end_turn", string(prompt.get(5, TimeUnit.SECONDS), "stopReason"));
        }
        assertFalse(process.alive());
    }

    @Test void explicitCancellationReleasesAnUntimedPromptAndTheConnectionRemainsUsable() throws Exception {
        var events = new LinkedBlockingQueue<AgentEvent>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process(), events::add)) {
            initialize(connection);
            var prompt = workers.submit(() -> connection.prompt(object("scenario", "controlled"), () -> false));
            awaitStarted(events); assertFalse(prompt.isDone()); connection.cancel();
            assertEquals("cancelled", string(prompt.get(5, TimeUnit.SECONDS), "stopReason"));
            assertTrue(connection.alive()); connection.request("fixture/fence", object(), CONTROL_TIMEOUT);
        }
    }

    @Test void disconnectAndMalformedProtocolReleaseBothThePromptAndPendingControlRequest() throws Exception {
        for (String method : List.of("fixture/disconnect", "fixture/malformed")) {
            var events = new LinkedBlockingQueue<AgentEvent>();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process(), events::add)) {
                initialize(connection);
                var prompt = workers.submit(() -> connection.prompt(object("scenario", "controlled"), () -> false));
                awaitStarted(events);
                transportFailure(assertThrows(ExecutionException.class, () -> connection.request(method, object(), CONTROL_TIMEOUT)));
                transportFailure(assertThrows(ExecutionException.class, () -> prompt.get(5, TimeUnit.SECONDS)));
                assertFalse(connection.alive());
            }
        }
    }

    @Test void closingTheConnectionReleasesAnUntimedPromptWithoutWaitingForTheAgent() throws Exception {
        var events = new LinkedBlockingQueue<AgentEvent>(); var process = process();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process, events::add)) {
            initialize(connection);
            var prompt = workers.submit(() -> connection.prompt(object("scenario", "controlled"), () -> false));
            awaitStarted(events); connection.close();
            transportFailure(assertThrows(ExecutionException.class, () -> prompt.get(5, TimeUnit.SECONDS)));
            assertFalse(process.alive());
            assertThrows(IOException.class, () -> connection.prompt(object(), () -> false));
        }
    }

    @Test void closingAfterATerminalResponseDoesNotWaitForTheBorrowedWriterMonitor() throws Exception {
        var events = new LinkedBlockingQueue<AgentEvent>(); var process = process();
        var writer = new OutputStreamWriter(process.process().getOutputStream(), StandardCharsets.UTF_8);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        Thread holder = null;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process, events::add, writer)) {
            try {
                initialize(connection);
                var prompt = workers.submit(() -> connection.prompt(object("scenario", "controlled"), () -> false));
                awaitStarted(events); connection.request("fixture/finish", object(), CONTROL_TIMEOUT);
                assertEquals("end_turn", string(prompt.get(5, TimeUnit.SECONDS), "stopReason"));
                holder = Thread.ofPlatform().name("agent-writer-shutdown-test").start(() -> {
                    synchronized (writer) {
                        held.countDown();
                        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                });
                assertTrue(held.await(3, TimeUnit.SECONDS));
                workers.submit(connection::close).get(8, TimeUnit.SECONDS);
                assertFalse(connection.alive()); assertFalse(process.alive());
                assertThrows(IOException.class, () -> { process.process().getOutputStream().write(1); process.process().getOutputStream().flush(); });
            } finally {
                release.countDown(); if (holder != null) holder.join(3000);
            }
        }
    }

    @Test void connectionCloseRacingRegistrationCannotStrandUnboundedWaiters() throws Exception {
        var ready = new CountDownLatch(8); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process(), ignored -> { })) {
            initialize(connection);
            var prompts = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) prompts.add(workers.submit(() -> {
                ready.countDown(); release.await(); return connection.prompt(object("scenario", "controlled"), () -> false);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); release.countDown(); connection.close();
            for (Future<?> prompt : prompts) transportFailure(assertThrows(ExecutionException.class, () -> prompt.get(5, TimeUnit.SECONDS)));
        } finally { release.countDown(); }
    }

    @Test void completedUntimedPromptsReleaseTheirRequestSlotsInsteadOfExhaustingTheConnection() throws Exception {
        var events = new LinkedBlockingQueue<AgentEvent>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var connection = new AcpConnection(process(), events::add)) {
            initialize(connection);
            for (int i = 0; i < 40; i++) {
                var prompt = workers.submit(() -> connection.prompt(object("scenario", "controlled"), () -> false));
                awaitStarted(events); connection.request("fixture/finish", object(), CONTROL_TIMEOUT);
                assertEquals("end_turn", string(prompt.get(5, TimeUnit.SECONDS), "stopReason"));
            }
            assertTrue(connection.alive());
        }
    }

    private AgentProcess process() throws IOException {
        return new AgentProcess(Path.of(System.getProperty("java.home"), "bin", "java"), temporary,
                List.of("-cp", System.getProperty("java.class.path"), FakeAcpProcess.class.getName()), Map.of());
    }
    private static void initialize(AcpConnection connection) throws Exception {
        connection.request("initialize", object(), CONTROL_TIMEOUT); connection.session("session");
    }
    private static void awaitStarted(BlockingQueue<AgentEvent> events) throws Exception {
        AgentEvent event = events.poll(5, TimeUnit.SECONDS);
        assertTrue(event instanceof AgentEvent.Text text && text.text().equals("Started"), "Controlled prompt did not start");
    }
    private static void transportFailure(Throwable failure) {
        boolean io = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(cause instanceof TimeoutException, "The test deadline expired instead of the connection releasing its waiter");
            if (cause instanceof IOException) io = true;
        }
        assertTrue(io, "Expected a protocol/connection failure");
    }
}
