package dev.rilvune.agents.application;

import dev.rilvune.agents.domain.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;

@Timeout(15)
class AgentServiceTest {
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicInteger installs = new AtomicInteger();
    private final AtomicInteger installationChecks = new AtomicInteger();
    private final AtomicReference<String> unsupportedReason = new AtomicReference<>();
    private final List<FakeRuntime> runtimes = new CopyOnWriteArrayList<>();
    private final AtomicReference<CompletableFuture<Void>> preflight = new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final dev.rilvune.agents.support.InMemoryAgentChatStateStore chatStore = new dev.rilvune.agents.support.InMemoryAgentChatStateStore();
    private final Map<String, List<AgentEvent>> histories = new ConcurrentHashMap<>();
    private final Map<String, AgentConfiguration> configurations = new ConcurrentHashMap<>();
    private final Map<String, Path> sessionProjects = new ConcurrentHashMap<>();
    private final AtomicInteger sessionIds = new AtomicInteger();
    private AgentService service;
    @AfterEach void close() throws Exception { if (service != null) { service.quiesce().get(5, TimeUnit.SECONDS); service.close(); } scheduler.shutdownNow(); workers.shutdownNow(); }
    private void start(boolean existing, boolean authenticated) {
        start(existing, authenticated, AgentSettings.defaults());
    }
    private void start(boolean existing, boolean authenticated, AgentSettings settings) {
        installed.set(existing);
        service = new AgentService(new AgentRuntimeInstaller() {
            public Optional<String> unsupportedReason() { return Optional.ofNullable(unsupportedReason.get()); }
            public Optional<Path> installed() { installationChecks.incrementAndGet(); return installed.get() ? Optional.of(Path.of("agent")) : Optional.empty(); }
            public Path install(Consumer<String> progress) { installs.incrementAndGet(); installed.set(true); return Path.of("agent"); }
        }, (executable, project, provider, events) -> { var runtime = new FakeRuntime(events, authenticated, project); runtimes.add(runtime); return runtime; },
                new AgentEngine("opencode", "OpenCode"), new AgentProvider("openai", "Codex", "openai/", "ChatGPT"), chatStore, workers, scheduler, Runnable::run,
                project -> preflight.get(), () -> { });
        service.settings(settings);
        service.project(Path.of("/project"));
    }
    @Test void installAuthenticationAndChatAreDistinctStates() throws Exception {
        start(false, false); await(() -> service.snapshot().phase() == NOT_INSTALLED);
        service.send("blocked"); assertTrue(service.snapshot().messages().isEmpty());
        service.connect(); await(() -> service.snapshot().phase() == AUTHORIZING); assertEquals(1, installs.get());
        service.connect(); await(() -> service.snapshot().login() != null);
        assertEquals(AUTHORIZING, service.snapshot().phase());
        runtimes.getFirst().login.complete(null); await(() -> service.snapshot().phase() == READY);
        assertEquals("openai/codex", service.snapshot().selectedModel());
        service.send("Hello"); await(() -> service.snapshot().phase() == BUSY && runtimes.getFirst().prompts.get() == 1);
        runtimes.getFirst().events.accept(new AgentEvent.Text("Streamed reply"));
        assertTrue(service.snapshot().messages().stream().anyMatch(message -> message.text().equals("Streamed reply")));
        runtimes.getFirst().prompt.complete(null); await(() -> service.snapshot().phase() == READY);
    }
    @Test void unsupportedPlatformsCannotInstallStartOrRetryEvenWithACachedBinary() {
        String reason = "OpenCode is not available for macOS / riscv64. Downloading is disabled.";
        unsupportedReason.set(reason);
        start(true, true);
        assertEquals(UNSUPPORTED, service.snapshot().phase()); assertEquals(reason, service.snapshot().status());
        service.connect(); service.connect();
        assertFalse(service.send("blocked"));
        assertEquals(0, installs.get()); assertEquals(0, installationChecks.get()); assertTrue(runtimes.isEmpty());
        service.project(null); assertEquals(UNSUPPORTED, service.snapshot().phase());
        service.project(Path.of("/another-project")); assertEquals(UNSUPPORTED, service.snapshot().phase());
        assertEquals(reason, service.snapshot().details().progress().detail());
        assertEquals(0, installationChecks.get());
    }
    @Test void cancellationDuringFilePreflightDoesNotSendLater() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        var pending = new CompletableFuture<Void>(); preflight.set(pending);
        service.send("Must not run"); service.cancel(); pending.complete(null);
        Thread.sleep(80);
        assertEquals(READY, service.snapshot().phase()); assertEquals(0, runtimes.getFirst().prompts.get());
        assertEquals("Must not run", service.snapshot().draft());
    }
    @Test void cancellingBrowserSignInClosesItsRuntimeAndRejectsLateCompletion() throws Exception {
        start(true, false); await(() -> service.snapshot().phase() == AUTH_REQUIRED);
        FakeRuntime old = runtimes.getFirst();
        service.connect(); old.loginStarted.get(5, TimeUnit.SECONDS);
        service.connect(); assertEquals(1, old.loginAttempts.get());
        assertFalse(service.send("Not authorized"));
        service.cancel(); await(() -> runtimes.size() == 2 && service.snapshot().phase() == AUTH_REQUIRED);
        old.login.complete(null);
        assertFalse(old.alive()); assertNull(service.snapshot().login());
        assertEquals(AUTH_REQUIRED, service.snapshot().phase());
        service.connect(); runtimes.getLast().loginStarted.get(5, TimeUnit.SECONDS);
        runtimes.getLast().login.complete(null); await(() -> service.snapshot().phase() == READY);
        assertNull(service.snapshot().login());
    }
    @Test void cancellationOvertakingWorkerStartupNeverSendsThePrompt() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime runtime = runtimes.getFirst(); runtime.sendGate = new CompletableFuture<>();
        service.send("Must not be sent later"); runtime.promptStarted.get(5, TimeUnit.SECONDS);
        service.cancel(); runtime.sendGate.complete(null);
        await(() -> service.snapshot().phase() == READY); assertEquals(0, runtime.prompts.get());
    }
    @Test void nextTurnWaitsUntilCancellationHasActuallyBeenDelivered() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime runtime = runtimes.getFirst(); runtime.cancelGate = new CompletableFuture<>();
        service.send("Work"); await(() -> runtime.prompts.get() == 1);
        service.cancel(); runtime.prompt.complete(null);
        assertFalse(service.send("Next turn")); assertEquals(BUSY, service.snapshot().phase());
        runtime.cancelGate.complete(null); await(() -> service.snapshot().phase() == READY);
    }
    @Test void projectTransitionRejectsLateOutputAndNeverReusesOldRuntime() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime old = runtimes.getFirst(); service.send("Old project"); await(() -> old.prompts.get() == 1);
        service.quiesce().get(5, TimeUnit.SECONDS); service.project(Path.of("/next"));
        await(() -> service.snapshot().phase() == READY); old.events.accept(new AgentEvent.Text("late output"));
        assertFalse(old.alive()); assertEquals(2, runtimes.size()); assertTrue(service.snapshot().messages().isEmpty());
    }
    @Test void disablingChatRestorationStartsANewChatWithoutDeletingSavedChats() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        String original = service.snapshot().chatId();
        service.settings(new AgentSettings(true, true, false, 100, 1));
        service.quiesce().get(5, TimeUnit.SECONDS); service.project(Path.of("/project"));
        await(() -> service.snapshot().phase() == READY);
        assertNotEquals(original, service.snapshot().chatId());
        assertTrue(histories.containsKey(original));
    }
    @Test void permissionsRequireAnExplicitAdvertisedChoiceAndOutputIsBounded() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY); service.send("Work");
        FakeRuntime runtime = runtimes.getFirst(); await(() -> runtime.prompts.get() == 1);
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("p", "Run test", "./gradlew test", List.of(new AgentPermission.Option("once", "Allow once", "allow_once")))));
        assertEquals(1, service.snapshot().permissions().size()); service.permission("p", "invalid"); assertEquals(0, runtime.answers.get());
        service.permission("p", "once"); await(() -> runtime.answers.get() == 1);
        for (int index = 0; index < 10; index++) runtime.events.accept(new AgentEvent.Text("x".repeat(20_000)));
        assertTrue(service.snapshot().messages().stream().mapToInt(message -> message.text().length()).sum() < 270_000);
    }
    @Test void failedPreflightKeepsAgentReadyWithoutExecuting() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        preflight.set(CompletableFuture.failedFuture(new IllegalStateException("File conflict")));
        service.send("Unsafe"); assertEquals(READY, service.snapshot().phase()); assertEquals(0, runtimes.getFirst().prompts.get());
        assertTrue(service.snapshot().messages().stream().anyMatch(message -> message.text().contains("File conflict")));
    }
    @Test void supervisorRestartsDeadProcessWithoutReplayingPrompt() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime old = runtimes.getFirst(); service.send("Work that must not be replayed"); await(() -> old.prompts.get() == 1);
        old.events.accept(new AgentEvent.Tool(new AgentToolCall("interrupted", "Inspect project", AgentToolCall.Status.RUNNING, "read files", "")));
        old.alive = false;
        await(() -> runtimes.size() == 2 && service.snapshot().phase() == READY);
        assertEquals(0, runtimes.getLast().prompts.get());
        AgentToolMessage interrupted = service.snapshot().messages().stream().filter(AgentToolMessage.class::isInstance).map(AgentToolMessage.class::cast).findFirst().orElseThrow();
        assertEquals(AgentToolCall.Status.CANCELLED, interrupted.call().status());
    }
    @Test void disablingProjectChatRestorationDoesNotDiscardTheActiveChatDuringCrashRecovery() throws Exception {
        start(true, true, new AgentSettings(true, true, false, 2000, 4));
        await(() -> service.snapshot().phase() == READY);
        String chat = service.snapshot().chatId();
        service.renameConversation("Investigation");
        FakeRuntime old = runtimes.getFirst();
        service.send("Investigate the failure"); await(() -> old.prompts.get() == 1);
        old.events.accept(new AgentEvent.Text("Investigation in progress"));
        service.updateDraft("Follow up after recovery");
        old.alive = false;
        await(() -> runtimes.size() == 2 && service.snapshot().phase() == READY);

        assertEquals(chat, service.snapshot().chatId());
        assertEquals("Investigation", service.snapshot().chatTitle());
        assertEquals("Follow up after recovery", service.snapshot().draft());
        assertTrue(service.snapshot().messages().stream().anyMatch(message -> message.text().equals("Investigate the failure")));
        assertTrue(service.snapshot().messages().stream().anyMatch(message -> message.text().equals("Investigation in progress")));
        assertEquals(0, runtimes.getLast().sessions.get());
        assertEquals(0, runtimes.getLast().prompts.get());
    }
    @Test void failedProcessCleanupRetainsOwnershipAndCanBeRetried() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime prior = runtimes.getFirst(); prior.closeFailures.set(1);
        assertThrows(ExecutionException.class, () -> service.quiesce().get(5, TimeUnit.SECONDS));
        assertEquals(ERROR, service.snapshot().phase()); assertTrue(service.snapshot().status().contains("Process has not stopped"));
        assertThrows(IllegalStateException.class, () -> service.project(Path.of("/next")));
        service.quiesce().get(5, TimeUnit.SECONDS); assertFalse(prior.alive());
        service.project(Path.of("/next")); await(() -> service.snapshot().phase() == READY);
        assertEquals(2, runtimes.size());
    }

    @Test void closingAfterACompletedPromptWaitsForCleanupThenAllowsTheLifecycleToFinish() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime prior = runtimes.getFirst(); service.send("Finish this task"); prior.promptStarted.get(5, TimeUnit.SECONDS);
        prior.prompt.complete(null); await(() -> service.snapshot().phase() == READY);
        prior.closeGate = new CompletableFuture<>();
        try {
            CompletableFuture<Void> stopping = service.quiesce(); prior.closeStarted.get(5, TimeUnit.SECONDS);
            assertEquals(STOPPING, service.snapshot().phase()); assertFalse(stopping.isDone());
            assertThrows(IllegalStateException.class, () -> service.project(Path.of("/next")));
            prior.closeGate.complete(null); stopping.get(5, TimeUnit.SECONDS); assertFalse(prior.alive());
            service.project(Path.of("/next")); await(() -> service.snapshot().phase() == READY);
        } finally { prior.closeGate.complete(null); }
    }

    @Test void cleanupTimeoutKeepsUsefulRetryGuidanceWhenTheUnderlyingTimeoutHasNoMessage() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime prior = runtimes.getFirst();
        prior.closeProblem = new IllegalStateException("The agent process or its pipes did not close; retry closing the project", new TimeoutException());
        assertThrows(ExecutionException.class, () -> service.quiesce().get(5, TimeUnit.SECONDS));
        assertEquals(ERROR, service.snapshot().phase()); assertTrue(service.snapshot().status().contains("retry closing the project"));
        assertThrows(IllegalStateException.class, () -> service.project(Path.of("/next")));
        service.quiesce().get(5, TimeUnit.SECONDS); assertFalse(prior.alive());
    }
    @Test void textAndToolsRemainInChronologicalOrderAndToolUpdatesKeepTheirIdentity() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY); service.send("Investigate");
        FakeRuntime runtime = runtimes.getFirst(); await(() -> runtime.prompts.get() == 1);
        runtime.events.accept(new AgentEvent.Text("Before the tool."));
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("test", "Run tests", AgentToolCall.Status.RUNNING, "./gradlew test", "")));
        runtime.events.accept(new AgentEvent.Text("After the tool."));
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("test", "Run tests", AgentToolCall.Status.COMPLETED, "./gradlew test", "All passed")));
        var messages = service.snapshot().messages();
        assertEquals(List.of(AgentMessage.Role.USER, AgentMessage.Role.ASSISTANT, AgentMessage.Role.TOOL, AgentMessage.Role.ASSISTANT), messages.stream().map(AgentMessage::role).toList());
        assertEquals("Before the tool.", messages.get(1).text()); assertEquals("After the tool.", messages.getLast().text());
        assertEquals("All passed", ((AgentToolMessage) messages.get(2)).call().output());
    }
    @Test void newChatCreatesARealSessionAndDoesNotDiscardTheCurrentChatWhileBusy() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY); service.send("First chat");
        FakeRuntime runtime = runtimes.getFirst(); await(() -> runtime.prompts.get() == 1);
        service.newConversation(); assertEquals(1, runtime.sessions.get()); assertFalse(service.snapshot().messages().isEmpty());
        runtime.prompt.complete(null); await(() -> service.snapshot().phase() == READY);
        service.newConversation(); await(() -> runtime.sessions.get() == 2 && service.snapshot().phase() == READY);
        assertTrue(service.snapshot().messages().isEmpty()); assertEquals(1, runtimes.size());
        assertEquals("openai/codex", service.snapshot().selectedModel());
    }
    @Test void failedNewSessionKeepsTheOldConversationReadable() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY); service.send("Keep this chat");
        FakeRuntime runtime = runtimes.getFirst(); await(() -> runtime.prompts.get() == 1);
        runtime.prompt.complete(null); await(() -> service.snapshot().phase() == READY);
        runtime.sessionFailure = true; service.newConversation(); await(() -> service.snapshot().phase() == ERROR);
        assertEquals("Keep this chat", service.snapshot().messages().getFirst().text());
    }
    @Test void savedChatsRestoreOriginalContextDraftsNamesAndEffortWithoutResendingPrompts() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime runtime = runtimes.getFirst(); String first = service.snapshot().chatId();
        service.send("Investigate the first issue"); await(() -> runtime.prompts.get() == 1);
        runtime.events.accept(new AgentEvent.Text("The original answer."));
        runtime.prompt.complete(null); await(() -> service.snapshot().phase() == READY);
        service.selectEffort("high"); await(() -> service.snapshot().phase() == READY && service.snapshot().selectedEffort().equals("high"));
        service.renameConversation("First investigation"); service.updateDraft("Follow up on the first issue");
        service.newConversation(); await(() -> service.snapshot().phase() == READY && !service.snapshot().chatId().equals(first));
        String second = service.snapshot().chatId(); assertEquals("", service.snapshot().draft());
        service.updateDraft("Independent second draft"); service.renameConversation("Second investigation");
        service.switchConversation(first); await(() -> service.snapshot().phase() == READY && service.snapshot().chatId().equals(first));
        assertEquals("Follow up on the first issue", service.snapshot().draft());
        assertEquals("First investigation", service.snapshot().chatTitle()); assertEquals("high", service.snapshot().selectedEffort());
        assertEquals(List.of("Investigate the first issue", "The original answer."), service.snapshot().messages().stream().map(AgentMessage::text).toList());
        assertEquals(1, runtime.prompts.get()); assertEquals(2, service.snapshot().history().chats().size());
        service.quiesce().get(5, TimeUnit.SECONDS); service.project(Path.of("/project"));
        await(() -> service.snapshot().phase() == READY);
        assertEquals(first, service.snapshot().chatId()); assertEquals("Follow up on the first issue", service.snapshot().draft());
        assertEquals(0, runtimes.getLast().prompts.get());
        service.switchConversation(second); await(() -> service.snapshot().phase() == READY && service.snapshot().chatId().equals(second));
        assertEquals("Independent second draft", service.snapshot().draft());
        service.deleteConversation(); await(() -> service.snapshot().phase() == READY && service.snapshot().chatId().equals(first));
        assertFalse(histories.containsKey(second)); assertEquals(1, service.snapshot().history().chats().size());
        service.quiesce().get(5, TimeUnit.SECONDS);
        assertFalse(chatStore.load(Path.of("/project")).preferences().containsKey(second));
    }
    @Test void busyChatCannotSwitchContextAndEmptyNewChatIsReused() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        FakeRuntime runtime = runtimes.getFirst(); String first = service.snapshot().chatId();
        service.newConversation(); assertEquals(1, runtime.sessions.get());
        service.updateDraft("Saved draft"); service.newConversation(); await(() -> service.snapshot().phase() == READY && !service.snapshot().chatId().equals(first));
        String second = service.snapshot().chatId(); service.send("Working"); await(() -> runtime.prompts.get() == 1);
        service.switchConversation(first); assertEquals(second, service.snapshot().chatId());
        runtime.prompt.complete(null); await(() -> service.snapshot().phase() == READY);
        service.switchConversation(first); await(() -> service.snapshot().phase() == READY && service.snapshot().chatId().equals(first));
        assertEquals("Saved draft", service.snapshot().draft());
    }
    @Test void failedPreferenceFlushIsReportedAndCanBeRetriedWithoutLosingDraft() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        chatStore.failWrites = true; service.updateDraft("Must survive failed write");
        assertThrows(ExecutionException.class, () -> service.quiesce().get(5, TimeUnit.SECONDS));
        await(() -> !service.snapshot().persistenceError().isEmpty());
        chatStore.failWrites = false; service.quiesce().get(5, TimeUnit.SECONDS);
        assertEquals("Must survive failed write", chatStore.load(Path.of("/project")).preferences().get(service.snapshot().chatId()).draft());
    }
    @Test void projectShutdownFreezesDraftAndNameUntilTheNextActivation() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        String id = service.snapshot().chatId(); service.updateDraft("Saved before closing"); service.renameConversation("Original name");
        service.quiesce().get(5, TimeUnit.SECONDS);
        service.updateDraft("Late edit"); service.renameConversation("Late name");
        assertEquals(new AgentChatState.Preference("Original name", "Saved before closing"), chatStore.load(Path.of("/project")).preferences().get(id));
        assertEquals("Saved before closing", service.snapshot().draft()); assertEquals("Original name", service.snapshot().chatTitle());
    }
    @Test void preparationFailureRestoresTheUnsentPromptWithoutOverwritingANewerDraft() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        var failed = new CompletableFuture<Void>(); preflight.set(failed);
        service.updateDraft("Original unsent message"); long before = service.snapshot().draftRevision();
        assertTrue(service.send("Original unsent message")); assertEquals("", service.snapshot().draft());
        failed.completeExceptionally(new IllegalStateException("Disk unavailable"));
        assertEquals(READY, service.snapshot().phase()); assertEquals("Original unsent message", service.snapshot().draft());
        assertTrue(service.snapshot().draftRevision() > before); assertEquals(0, runtimes.getFirst().prompts.get());
        var second = new CompletableFuture<Void>(); preflight.set(second); service.send("Original unsent message"); service.updateDraft("New draft typed while saving");
        second.completeExceptionally(new IllegalStateException("Disk unavailable"));
        assertEquals("New draft typed while saving", service.snapshot().draft()); assertEquals(READY, service.snapshot().phase());
    }
    @Test void shutdownDuringFilePreparationPreservesTheUnsentDraftForTheNextActivation() throws Exception {
        start(true, true); await(() -> service.snapshot().phase() == READY);
        var pending = new CompletableFuture<Void>(); preflight.set(pending);
        service.send("Keep this unsent request"); service.quiesce().get(5, TimeUnit.SECONDS);
        pending.complete(null); service.project(Path.of("/project")); await(() -> service.snapshot().phase() == READY);
        assertEquals("Keep this unsent request", service.snapshot().draft()); assertEquals(0, runtimes.getFirst().prompts.get());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(10); }
        fail("Agent state did not settle");
    }
    public final class FakeRuntime implements AgentRuntime {
        public final Consumer<AgentEvent> events;
        public final CompletableFuture<Void> login = new CompletableFuture<>(), prompt = new CompletableFuture<>();
        public final CompletableFuture<Void> promptStarted = new CompletableFuture<>();
        public final CompletableFuture<Void> loginStarted = new CompletableFuture<>();
        public final CompletableFuture<Void> closeStarted = new CompletableFuture<>();
        public final AtomicInteger loginAttempts = new AtomicInteger();
        public volatile CompletableFuture<Void> sendGate = CompletableFuture.completedFuture(null), cancelGate = CompletableFuture.completedFuture(null), closeGate = CompletableFuture.completedFuture(null);
        public final AtomicInteger prompts = new AtomicInteger(), answers = new AtomicInteger(), closeFailures = new AtomicInteger();
        public volatile boolean alive = true;
        public volatile RuntimeException closeProblem;
        private final boolean authenticated;
        private String currentSession;
        private final Path directory;
        public FakeRuntime(Consumer<AgentEvent> events, boolean authenticated, Path directory) {
            this.directory = directory;
            this.events = event -> { if (currentSession != null) histories.get(currentSession).add(event); events.accept(event); };
            this.authenticated = authenticated;
        }
        private AgentConfiguration configuration() { return new AgentConfiguration(List.of(new AgentModel("openai/codex", "Codex")), "openai/codex", List.of(new AgentEffort("low", "Low"), new AgentEffort("high", "High")), "low"); }
        public Setup start() { return new Setup(authenticated, authenticated ? listSessions("") : AgentChatPage.empty()); }
        public AgentLogin beginLogin() { loginAttempts.incrementAndGet(); return new AgentLogin(URI.create("https://example.com/login"), "Complete sign-in in your browser"); }
        public Setup awaitLogin() throws Exception { loginStarted.complete(null); login.get(); return new Setup(true, listSessions("")); }
        public final AtomicInteger sessions = new AtomicInteger();
        public boolean sessionFailure;
        public AgentSession newSession() {
            sessions.incrementAndGet(); if (sessionFailure) throw new IllegalStateException("New session failed");
            currentSession = "chat-" + sessionIds.incrementAndGet(); histories.put(currentSession, new CopyOnWriteArrayList<>()); sessionProjects.put(currentSession, directory); configurations.put(currentSession, configuration());
            return new AgentSession(currentSession, configuration());
        }
        public AgentSession loadSession(String id, Consumer<AgentEvent> replay) {
            if (!histories.containsKey(id) || !directory.equals(sessionProjects.get(id))) throw new IllegalStateException("Unknown chat"); currentSession = id;
            histories.get(id).forEach(replay); return new AgentSession(id, configurations.get(id));
        }
        public AgentChatPage listSessions(String cursor) {
            return new AgentChatPage(histories.keySet().stream().filter(id -> directory.equals(sessionProjects.get(id))).sorted().map(id -> new AgentChat(id, "Chat " + id, java.time.Instant.EPOCH)).toList(), "");
        }
        public void deleteSession(String id) { histories.remove(id); configurations.remove(id); }
        public AgentConfiguration selectModel(String id) { return configurations.get(currentSession); }
        public AgentConfiguration selectEffort(String id) {
            var prior = configurations.get(currentSession); var next = new AgentConfiguration(prior.models(), prior.selectedModel(), prior.efforts(), id); configurations.put(currentSession, next); return next;
        }
        public void prompt(String text, BooleanSupplier cancelled) throws Exception {
            promptStarted.complete(null); sendGate.get(); if (cancelled.getAsBoolean()) return;
            histories.get(currentSession).add(new AgentEvent.Text(text, AgentMessage.Role.USER, "user-" + prompts.get()));
            prompts.incrementAndGet(); prompt.get();
        }
        public void cancel() throws Exception { cancelGate.get(); prompt.complete(null); }
        public void permission(String id, String option) { answers.incrementAndGet(); }
        public boolean alive() { return alive; }
        public void close() {
            closeStarted.complete(null); closeGate.join();
            if (closeProblem != null) { RuntimeException failure = closeProblem; closeProblem = null; throw failure; }
            if (closeFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) throw new IllegalStateException("Process has not stopped");
            alive = false; sendGate.complete(null); cancelGate.complete(null);
            login.completeExceptionally(new IllegalStateException("closed")); prompt.completeExceptionally(new IllegalStateException("closed"));
        }
    }
}
