package dev.rilvune.agents.application;

import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.domain.content.AgentContent;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;
import static dev.rilvune.agents.domain.AgentMessage.Role.*;

/** Owns one project-scoped agent lifecycle and bounded chat state. All adapters are explicit ports. */
public final class AgentService implements AutoCloseable {
    private final AgentRuntimeInstaller installer;
    private final AgentRuntimeFactory factory;
    private final AgentProvider provider;
    private final AgentEngine engine;
    private final Executor workers, notifications;
    private final ScheduledExecutorService scheduler;
    private final Function<Path, CompletableFuture<Void>> preparePrompt;
    private final Runnable filesChanged;
    private final List<Consumer<AgentSnapshot>> listeners = new ArrayList<>();
    private AgentTranscript transcript = new AgentTranscript();
    private volatile AgentSettings settings = AgentSettings.defaults();
    private AgentSessionState sessionState = new AgentSessionState();
    private final AgentChatStatePersistence persistence;
    private final AgentAttachmentStore attachments;
    private final Set<String> attachmentImports = new HashSet<>();
    private final Set<CompletableFuture<Void>> attachmentTasks = new HashSet<>();
    private long attachmentGeneration;
    private final Map<String, AgentChatState.Preference> preferences = new HashMap<>();
    private final List<AgentChat> chats = new ArrayList<>();
    private String selectedChat = "", nextChatCursor = "", persistenceError = "";
    private boolean historyLoading;
    private boolean restoreChatOnProjectOpen;
    private AgentSession session;
    private String finalStatus = "Ready";
    private AgentChatState lastRemembered;
    private final Map<String, AgentPermission> permissions = new LinkedHashMap<>();
    private final ScheduledFuture<?> observation;
    private AgentRuntime runtime, retiring;
    private Path project;
    private AgentSnapshot.Phase phase = NO_PROJECT;
    private String status = "Open a project to use AI Assistant";
    private AgentLogin login;
    private long generation, turn, draftRevision;
    private String pendingPrompt = "", pendingPromptChat = "";
    private List<AgentAttachment> pendingAttachments = List.of();
    private boolean prompting, cancelling, connectAfterStart;
    private AtomicBoolean promptCancellation = new AtomicBoolean();
    private int restarts;
    private boolean scheduled, closed;
    private CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);

    public AgentService(AgentRuntimeInstaller installer, AgentRuntimeFactory factory, AgentEngine engine, AgentProvider provider,
                        AgentChatStateStore chatStore, Executor workers, ScheduledExecutorService scheduler, Executor notifications,
                        Function<Path, CompletableFuture<Void>> preparePrompt, Runnable filesChanged) {
        this(installer, factory, engine, provider, chatStore, workers, scheduler, notifications, preparePrompt, filesChanged, null);
    }
    public AgentService(AgentRuntimeInstaller installer, AgentRuntimeFactory factory, AgentEngine engine, AgentProvider provider,
                        AgentChatStateStore chatStore, Executor workers, ScheduledExecutorService scheduler, Executor notifications,
                        Function<Path, CompletableFuture<Void>> preparePrompt, Runnable filesChanged, AgentAttachmentStore attachments) {
        this.installer = installer; this.factory = factory; this.engine = engine; this.provider = provider;
        this.attachments = attachments;
        this.workers = workers; this.scheduler = scheduler; this.notifications = notifications;
        persistence = new AgentChatStatePersistence(chatStore, workers, scheduler, (target, failure) -> {
            synchronized (this) {
                if (!closed && Objects.equals(project, target)) { persistenceError = failure == null ? "" : error(failure); publish(); }
            }
        });
        this.preparePrompt = preparePrompt; this.filesChanged = filesChanged;
        installer.unsupportedReason().ifPresent(reason -> { phase = UNSUPPORTED; status = reason; });
        observation = scheduler.scheduleWithFixedDelay(this::observe, 2, 2, TimeUnit.SECONDS);
    }
    public synchronized AgentSnapshot snapshot() {
        return new AgentSnapshot(project, phase, status, engine, provider, session, login, cancelling, transcript.messages(),
                List.copyOf(permissions.values()), new AgentChatPage(displayChats(), nextChatCursor), title(selectedChat), draft(), draftRevision, persistenceError, transcript.truncated(), sessionState.snapshot(phase, status, cancelling, !permissions.isEmpty()), preference(selectedChat).attachments(), attachmentImports.contains(selectedChat));
    }
    public synchronized void settings(AgentSettings value) {
        settings = Objects.requireNonNull(value); transcript.settings(value); publish();
    }
    public synchronized AutoCloseable listen(Consumer<AgentSnapshot> listener) {
        listeners.add(listener); listener.accept(snapshot()); return () -> { synchronized (this) { listeners.remove(listener); } };
    }
    public synchronized void project(Path next) {
        if (closed) return;
        if (runtime != null || retiring != null || !stopping.isDone()) throw new IllegalStateException("Quiesce the agent before changing projects");
        project = next; login = null; permissions.clear(); session = null; restarts = 0;
        attachmentImports.clear(); attachmentGeneration++; pendingAttachments = List.of();
        restoreChatOnProjectOpen = settings.restoreLastChat();
        transcript = new AgentTranscript(); sessionState = new AgentSessionState(); preferences.clear(); chats.clear(); selectedChat = ""; nextChatCursor = ""; persistenceError = ""; historyLoading = false; lastRemembered = null; draftRevision = 0; pendingPrompt = ""; pendingPromptChat = "";
        transcript.settings(settings);
        long version = ++generation;
        var unsupported = installer.unsupportedReason();
        if (unsupported.isPresent()) { state(UNSUPPORTED, unsupported.orElseThrow()); return; }
        if (next == null) { state(NO_PROJECT, "Open a project to use AI Assistant"); return; }
        state(STARTING, "Checking agent component…");
        run(() -> {
            AgentChatState saved; String problem = "";
            try { saved = persistence.load(next); } catch (Exception failure) { saved = AgentChatState.empty(); problem = error(failure); }
            return new ProjectSetup(saved, problem, installer.installed());
        }, loaded -> {
            lastRemembered = loaded.state(); preferences.putAll(loaded.state().preferences()); selectedChat = loaded.state().selectedChat(); persistenceError = loaded.problem();
            if (loaded.executable().isPresent()) start(loaded.executable().orElseThrow(), version); else state(NOT_INSTALLED, "Install " + engine.name() + " to get started");
        }, version);
    }
    public synchronized void connect() {
        if (closed) return;
        var unsupported = installer.unsupportedReason();
        if (unsupported.isPresent()) { state(UNSUPPORTED, unsupported.orElseThrow()); return; }
        if (phase == AUTH_REQUIRED) { authenticate(); return; }
        if (project == null || !(phase == NOT_INSTALLED || phase == ERROR)) return;
        long version = ++generation; restarts = 0; connectAfterStart = true;
        state(INSTALLING, "Installing " + engine.name() + "…");
        CompletableFuture<Void> cleanup = retiring == null ? stopping : stopRuntime();
        run(() -> { cleanup.join(); return installer.install(message -> progress(version, message)); }, path -> start(path, version), version);
    }
    private synchronized void progress(long version, String message) { if (current(version)) state(INSTALLING, message); }
    private void start(Path executable, long version) {
        if (!current(version)) return;
        if (runtime != null || retiring != null || !stopping.isDone()) throw new IllegalStateException("Previous agent cleanup is incomplete");
        state(STARTING, "Starting " + engine.name() + "…");
        AgentRuntime next = factory.create(executable, project, provider, event -> event(version, event));
        runtime = next;
        run(next::start, this::setup, version);
    }
    private void setup(AgentRuntime.Setup setup) {
        login = null;
        if (!setup.authenticated()) {
            state(AUTH_REQUIRED, "Connect " + provider.name() + " to enable chat");
            boolean signIn = connectAfterStart; connectAfterStart = false; if (signIn) authenticate();
            return;
        }
        connectAfterStart = false; applyPage(setup.chats(), true);
        String choice = startupChat();
        AgentRuntime target = runtime; long version = generation;
        state(STARTING, choice.isEmpty() ? "Starting a chat…" : "Restoring your chat…");
        boolean listed = chats.stream().anyMatch(chat -> chat.id().equals(choice));
        String fallback = chats.isEmpty() ? "" : chats.getFirst().id();
        run(() -> {
            try { return load(target, choice); }
            catch (Exception missing) {
                // A deleted/stale preference must not trap startup in an endless retry loop.
                if (listed) throw missing;
                LoadedSession recovered = load(target, fallback);
                recovered.transcript().append(SYSTEM, "The previously selected chat could not be reopened. Choose a saved chat from the chat list.");
                return recovered;
            }
        }, this::activate, version);
    }
    private String startupChat() {
        // Recover an established session regardless of the preference for opening a project.
        if (session != null) return session.id();
        if (!restoreChatOnProjectOpen) return "";
        return !selectedChat.isEmpty() ? selectedChat : chats.isEmpty() ? "" : chats.getFirst().id();
    }
    private LoadedSession load(AgentRuntime target, String id) throws Exception {
        AgentTranscript history = new AgentTranscript(); AgentSessionState state = new AgentSessionState();
        history.settings(settings);
        AgentSession ready = id.isEmpty() ? target.newSession() : target.loadSession(id, event -> { history.event(event); state.event(event); });
        state.commands(ready.commands()); state.end(); history.finish(false); return new LoadedSession(ready, history, state);
    }
    private void activate(LoadedSession loaded) {
        session = loaded.session(); selectedChat = session.id(); transcript = loaded.transcript(); sessionState = loaded.state();
        transcript.settings(settings);
        preferences.putIfAbsent(selectedChat, new AgentChatState.Preference("", ""));
        if (chats.stream().noneMatch(chat -> chat.id().equals(selectedChat))) chats.add(new AgentChat(selectedChat, initialTitle(), java.time.Instant.now()));
        remember(); state(READY, "Ready");
    }
    private synchronized void authenticate() {
        if (phase != AUTH_REQUIRED || runtime == null) return;
        long version = generation; AgentRuntime target = runtime;
        state(AUTHORIZING, "Preparing sign-in…");
        run(target::beginLogin, authorization -> {
            login = authorization; state(AUTHORIZING, authorization.instructions());
            run(target::awaitLogin, this::setup, version);
        }, version);
    }
    public synchronized void newConversation() {
        if (closed || phase != READY || runtime == null) return;
        if (transcript.messages().isEmpty() && draft().isBlank() && preference(selectedChat).attachments().isEmpty() && !attachmentImports.contains(selectedChat)) return;
        AgentRuntime target = runtime; long version = generation; state(STARTING, "Starting a new chat…");
        run(() -> load(target, ""), loaded -> { activate(loaded); refreshChats(false); }, version);
    }
    public synchronized void switchConversation(String id) {
        if (phase != READY || runtime == null || id.equals(selectedChat) || chats.stream().noneMatch(chat -> chat.id().equals(id))) return;
        AgentRuntime target = runtime; state(STARTING, "Opening chat…");
        run(() -> load(target, id), this::activate, generation, failure -> {
            append(SYSTEM, "Could not open that chat: " + error(failure)); state(READY, "Could not open chat");
        });
    }
    public synchronized void renameConversation(String name) {
        if (closed || phase == STOPPING || selectedChat.isEmpty() || name.isBlank() || name.length() > 200) return;
        var current = preference(selectedChat); preferences.put(selectedChat, new AgentChatState.Preference(name.replace('\n', ' ').replace('\r', ' ').strip(), current.draft(), current.attachments())); remember(); publish();
    }
    public synchronized void deleteConversation() {
        if (phase != READY || runtime == null || selectedChat.isEmpty()) return;
        String removed = selectedChat; AgentRuntime target = runtime; state(STARTING, "Deleting chat…");
        run(() -> { target.deleteSession(removed); return null; }, ignored -> {
            preferences.remove(removed); chats.removeIf(chat -> chat.id().equals(removed)); selectedChat = ""; remember();
            String next = chats.isEmpty() ? "" : chats.getFirst().id();
            run(() -> load(target, next), this::activate, generation);
        }, generation);
    }
    public synchronized long updateDraft(String text) {
        if (project == null || selectedChat.isEmpty() || closed || phase == STOPPING || preference(selectedChat).draft().equals(text)) return draftRevision;
        var current = preference(selectedChat); preferences.put(selectedChat, new AgentChatState.Preference(current.title(), text, current.attachments())); draftRevision++; remember(); return draftRevision;
    }
    public Path attachmentPath(AgentAttachment attachment) { return Objects.requireNonNull(attachments, "Attachments are not configured").resolve(attachment); }
    public CompletableFuture<Void> attachFiles(List<Path> sources) {
        List<Path> files = List.copyOf(sources);
        if (files.isEmpty()) return CompletableFuture.completedFuture(null);
        if (files.size() > AgentAttachment.MAX_COUNT) return CompletableFuture.failedFuture(new IllegalArgumentException("Attach up to 10 files at once"));
        return importAttachments(() -> {
            List<AgentAttachment> imported = new ArrayList<>();
            for (Path file : files) imported.add(attachments.importFile(file));
            return imported;
        });
    }
    public CompletableFuture<Void> attachImage(byte[] png) {
        if (png.length > AgentAttachment.MAX_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("Clipboard image exceeds 20 MiB"));
        byte[] snapshot = png.clone(); return attachImage(() -> snapshot);
    }
    /** The UI supplies an encoder; it runs on a worker while sending this draft is suspended. */
    public CompletableFuture<Void> attachImage(Callable<byte[]> png) {
        return importAttachments(() -> List.of(attachments.saveImage(png.call())));
    }
    private synchronized CompletableFuture<Void> importAttachments(Callable<List<AgentAttachment>> operation) {
        if (attachments == null || closed || selectedChat.isEmpty() || !(phase == READY || phase == BUSY))
            return CompletableFuture.failedFuture(new IllegalStateException("Open a connected chat before attaching files"));
        if (attachmentImports.contains(selectedChat)) return CompletableFuture.failedFuture(new IllegalStateException("Wait for the current attachments to finish saving"));
        String chat = selectedChat; long version = attachmentGeneration;
        attachmentImports.add(chat); publish();
        CompletableFuture<Void> task = CompletableFuture.supplyAsync(() -> {
            try { return operation.call(); } catch (Exception failure) { throw new CompletionException(failure); }
        }, workers).handle((imported, failure) -> {
            synchronized (this) {
                if (closed || attachmentGeneration != version) throw new CompletionException(new CancellationException("Project closed before the attachment was saved"));
                attachmentImports.remove(chat);
                try {
                    if (failure != null) throw new CompletionException(failure);
                    if (!preferences.containsKey(chat)) throw new CancellationException("Chat was deleted");
                    var current = preference(chat); List<AgentAttachment> merged = new ArrayList<>(current.attachments());
                    for (var item : imported) if (merged.stream().noneMatch(existing -> existing.key().equals(item.key()))) merged.add(item);
                    if (!AgentAttachment.withinLimits(merged)) throw new IllegalArgumentException("Attach up to 10 files, totaling at most 20 MiB");
                    preferences.put(chat, new AgentChatState.Preference(current.title(), current.draft(), merged));
                    draftRevision++; remember(); return null;
                } finally { publish(); }
            }
        });
        attachmentTasks.add(task);
        task.whenComplete((ignored, failure) -> { synchronized (this) { attachmentTasks.remove(task); } });
        return task;
    }
    public synchronized void removeAttachment(String key) {
        if (closed || phase == STOPPING || selectedChat.isEmpty()) return;
        var current = preference(selectedChat);
        preferences.put(selectedChat, new AgentChatState.Preference(current.title(), current.draft(), current.attachments().stream().filter(item -> !item.key().equals(key)).toList()));
        draftRevision++; remember(); publish();
    }
    private void restoreUnsentPrompt() {
        if (selectedChat.equals(pendingPromptChat) && (!pendingPrompt.isEmpty() || !pendingAttachments.isEmpty())) {
            var current = preference(selectedChat); List<AgentAttachment> merged = new ArrayList<>(current.attachments());
            for (var attachment : pendingAttachments) if (merged.stream().noneMatch(item -> item.key().equals(attachment.key()))) merged.add(attachment);
            preferences.put(selectedChat, new AgentChatState.Preference(current.title(), current.draft().isBlank() ? pendingPrompt : current.draft(), merged));
            pendingAttachments = List.of(); draftRevision++; remember();
        }
    }
    private void preparationFailed(Throwable failure) {
        restoreUnsentPrompt(); pendingPrompt = ""; sessionState.end();
        append(SYSTEM, "Message was not sent: " + error(failure)); sessionState.problem("Message not sent · " + error(failure)); state(READY, "Resolve editor file problems before sending");
    }
    public synchronized void refreshChats(boolean more) {
        if (runtime == null || historyLoading || !(phase == READY || phase == BUSY)) return;
        String cursor = more ? nextChatCursor : ""; if (more && cursor.isEmpty()) return;
        historyLoading = true; AgentRuntime target = runtime;
        run(() -> target.listSessions(cursor), page -> { historyLoading = false; applyPage(page, !more); publish(); }, generation,
                failure -> { historyLoading = false; status = "Could not refresh saved chats: " + error(failure); publish(); });
    }
    private void applyPage(AgentChatPage page, boolean reset) {
        AgentChat active = chats.stream().filter(chat -> chat.id().equals(selectedChat)).findFirst().orElse(null);
        if (reset) chats.clear();
        for (AgentChat chat : page.chats()) { chats.removeIf(previous -> previous.id().equals(chat.id())); chats.add(chat); }
        if (active != null && chats.stream().noneMatch(chat -> chat.id().equals(active.id()))) chats.add(active);
        nextChatCursor = page.nextCursor();
    }
    public synchronized void selectModel(String id) {
        if (phase != READY || session == null || id.equals(session.configuration().selectedModel()) || session.configuration().models().stream().noneMatch(model -> model.id().equals(id))) return;
        AgentRuntime target = runtime; state(STARTING, "Selecting model…");
        run(() -> target.selectModel(id), configuration -> { session = session.withConfiguration(configuration); state(READY, "Ready"); }, generation);
    }
    public synchronized void selectEffort(String id) {
        if (phase != READY || session == null || id.equals(session.configuration().selectedEffort()) || session.configuration().efforts().stream().noneMatch(effort -> effort.id().equals(id))) return;
        AgentRuntime target = runtime; state(STARTING, "Selecting effort…");
        run(() -> target.selectEffort(id), configuration -> { session = session.withConfiguration(configuration); state(READY, "Ready"); }, generation);
    }
    private AgentChatState.Preference preference(String id) { return preferences.getOrDefault(id, new AgentChatState.Preference("", "")); }
    private String draft() { return preference(selectedChat).draft(); }
    private String initialTitle() {
        return transcript.messages().stream().filter(message -> message.role() == USER).findFirst().map(message -> {
            String text = message.text().strip().replace('\n', ' '); return text.substring(0, Math.min(80, text.length()));
        }).filter(title -> !title.isBlank()).orElseGet(() -> transcript.messages().stream().filter(AgentTextMessage.class::isInstance).map(AgentTextMessage.class::cast)
                .flatMap(message -> message.content().stream()).filter(AgentContent.Attachment.class::isInstance).map(AgentContent.Attachment.class::cast)
                .map(AgentContent.Attachment::name).map(name -> name.substring(0, Math.min(80, name.length()))).findFirst().orElse("New chat"));
    }
    private String title(String id) {
        String custom = preference(id).title();
        return !custom.isBlank() ? custom : chats.stream().filter(chat -> chat.id().equals(id)).findFirst().map(AgentChat::title).orElse("New chat");
    }
    private List<AgentChat> displayChats() {
        return chats.stream().map(chat -> new AgentChat(chat.id(), title(chat.id()), chat.updatedAt()))
                .sorted(Comparator.comparing(AgentChat::updatedAt).reversed()).toList();
    }
    private void remember() {
        if (project == null) return; AgentChatState state = new AgentChatState(selectedChat, preferences);
        if (state.equals(lastRemembered) && persistenceError.isEmpty()) return;
        lastRemembered = state; persistence.update(project, state);
    }
    private record LoadedSession(AgentSession session, AgentTranscript transcript, AgentSessionState state) { }
    private record ProjectSetup(AgentChatState state, String problem, Optional<Path> executable) { }
    public synchronized boolean send(String text) {
        if (phase != READY || attachmentImports.contains(selectedChat)) return false;
        AgentPrompt prompt;
        try { prompt = new AgentPrompt(text, preference(selectedChat).attachments()); } catch (IllegalArgumentException invalid) { return false; }
        long version = generation, request = ++turn; AgentRuntime target = runtime; Path directory = project; prompting = false; cancelling = false;
        AtomicBoolean cancelled = new AtomicBoolean(); promptCancellation = cancelled;
        pendingPrompt = text; pendingAttachments = prompt.attachments(); pendingPromptChat = selectedChat;
        finalStatus = "Ready"; sessionState.begin(); sessionState.waiting("Saving editor files…"); append(USER, text);
        for (var attachment : prompt.attachments()) transcript.event(new AgentEvent.Content(new AgentContent.Attachment(attachment.name(), attachment.mimeType(), attachments.resolve(attachment)), USER, AgentOrigin.MAIN));
        var currentDraft = preference(selectedChat); preferences.put(selectedChat, new AgentChatState.Preference(currentDraft.title(), "")); draftRevision++;
        if (transcript.messages().stream().filter(message -> message.role() == USER).count() == 1) {
            String id = selectedChat; chats.removeIf(chat -> chat.id().equals(id)); chats.add(new AgentChat(id, initialTitle(), java.time.Instant.now()));
        }
        remember(); state(BUSY, "Saving editor files…");
        CompletableFuture<Void> prepared;
        try {
            prepared = preparePrompt.apply(directory);
            if (!prompt.attachments().isEmpty()) prepared = prepared.thenRunAsync(() -> {
                try { for (var attachment : prompt.attachments()) attachments.verify(attachment); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, workers);
        } catch (Exception failure) { preparationFailed(failure); return true; }
        prepared.whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (!current(version) || request != turn) return;
                if (failure != null) { preparationFailed(failure); return; }
                prompting = true; sessionState.waiting("Waiting for the agent…"); state(BUSY, "Working…");
                run(() -> {
                    target.prompt(prompt, cancelled::get); return null;
                }, nothing -> {
                    if (request != turn) return;
                    prompting = false; pendingPrompt = ""; pendingAttachments = List.of(); permissions.clear(); sessionState.end();
                    if (cancelling) { status = "Finishing cancellation…"; publish(); } else { transcript.finish(cancelled.get()); if (cancelled.get() || finalStatus.equals("Stopped")) sessionState.stopped("Stopped"); state(READY, cancelled.get() ? "Stopped" : finalStatus); }
                    filesChanged.run(); refreshChats(false);
                }, version);
            }
        });
        return true;
    }
    public synchronized void cancel() {
        if (phase == AUTHORIZING) { restartAfterLogin(); return; }
        if (phase != BUSY || runtime == null || cancelling) return;
        promptCancellation.set(true); permissions.clear();
        if (!prompting) { turn++; restoreUnsentPrompt(); pendingPrompt = ""; sessionState.stopped("Cancelled before sending"); state(READY, "Cancelled before sending"); return; }
        AgentRuntime target = runtime; long version = generation, request = turn;
        cancelling = true; status = "Stopping…"; publish();
        run(() -> { target.cancel(); return null; }, ignored -> {
            if (phase == BUSY && request == turn) {
                cancelling = false;
                if (prompting) { status = "Waiting for cancellation…"; publish(); } else { settleTools(); sessionState.stopped("Stopped"); state(READY, "Stopped"); }
            }
        }, version);
        scheduler.schedule(() -> { synchronized (this) { if (current(version) && phase == BUSY && request == turn) fail(version, new IllegalStateException("Agent did not stop; reconnect before continuing")); } }, 15, TimeUnit.SECONDS);
    }
    private void restartAfterLogin() {
        long version = generation + 1;
        quiesce().whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (!current(version)) return;
                if (failure != null) { fail(version, failure); return; }
                state(STARTING, "Restarting " + engine.name() + "…");
                run(installer::installed, installed -> {
                    if (installed.isPresent()) start(installed.orElseThrow(), version); else state(NOT_INSTALLED, "Install " + engine.name());
                }, version);
            }
        });
    }
    public synchronized void permission(String request, String option) {
        AgentPermission pending = permissions.get(request);
        if (runtime == null || pending == null || pending.options().stream().noneMatch(item -> item.id().equals(option))) return;
        permissions.remove(request); publish(); AgentRuntime target = runtime;
        run(() -> { target.permission(request, option); return null; }, ignored -> { }, generation);
    }
    private synchronized void event(long version, AgentEvent event) {
        if (!current(version)) return;
        if (event instanceof AgentEvent.Configuration changed && session != null) {
            AgentConfiguration config = AgentConfiguration.from(changed.options(), provider.modelPrefix(), session.configuration().selectedModel());
            if (!config.models().isEmpty()) session = session.withConfiguration(config);
        } else if (event instanceof AgentEvent.Mode mode && session != null) {
            var options = session.configuration().options().stream().map(option -> option.category() == AgentConfigOption.Category.MODE ? new AgentConfigOption(option.id(), option.name(), option.category(), mode.id(), option.choices()) : option).toList();
            session = session.withConfiguration(AgentConfiguration.from(options, provider.modelPrefix(), session.configuration().selectedModel()));
        } else if (event instanceof AgentEvent.Metadata metadata && !metadata.title().isBlank()) {
            String id = selectedChat;
            for (int index = 0; index < chats.size(); index++) if (chats.get(index).id().equals(id)) chats.set(index, new AgentChat(id, metadata.title(), chats.get(index).updatedAt()));
        } else if (event instanceof AgentEvent.Resolved resolved) permissions.remove(resolved.id());
        if (phase == BUSY) {
            sessionState.event(event); transcript.event(event);
            if (event instanceof AgentEvent.Permission permission) {
                if (promptCancellation.get()) return;
                if (permissions.size() >= 8) { fail(version, new IllegalStateException("Too many pending permission requests")); return; }
                permissions.put(permission.request().id(), permission.request());
            } else if (event instanceof AgentEvent.Finished finished) {
                if (finished.reason().equals("not_sent")) restoreUnsentPrompt();
                finalStatus = switch (finished.reason()) { case "cancelled" -> "Stopped"; case "not_sent" -> "Cancelled before sending"; case "max_tokens", "max_turn_requests" -> "Response limit reached"; case "refusal" -> "Request declined"; default -> "Ready"; };
                if (!finalStatus.equals("Ready") && !finalStatus.equals("Stopped")) append(SYSTEM, finalStatus);
            }
        } else if (event instanceof AgentEvent.Commands || event instanceof AgentEvent.Usage) sessionState.event(event);
        publish();
    }
    public synchronized void answer(String id, AgentQuestionAnswer answer) {
        if (phase != BUSY || runtime == null || cancelling) return;
        AgentQuestion question = sessionState.question(id); if (question == null) return;
        if (answer.action() == AgentQuestionAnswer.Action.ACCEPT) question.validate(answer.values());
        AgentRuntime target = runtime; sessionState.event(new AgentEvent.Resolved(id)); publish();
        run(() -> { target.answer(id, answer); return null; }, ignored -> { }, generation);
    }
    public synchronized void selectOption(String id, String value) {
        if (phase != READY || session == null || session.configuration().options().stream().noneMatch(option -> option.id().equals(id) && option.choices().stream().anyMatch(choice -> choice.id().equals(value)))) return;
        AgentRuntime target = runtime; state(STARTING, "Updating session…");
        run(() -> target.selectOption(id, value), config -> { session = session.withConfiguration(config); state(READY, "Ready"); }, generation);
    }
    public synchronized void forkConversation() {
        if (phase != READY || runtime == null || session == null || !session.capabilities().contains(AgentSession.Capability.FORK)) return;
        AgentRuntime target = runtime; state(STARTING, "Branching chat…");
        run(() -> load(target, target.forkSession().id()), loaded -> { activate(loaded); refreshChats(false); }, generation);
    }
    private void append(AgentMessage.Role role, String text) { transcript.append(role, text); }
    private void settleTools() { transcript.settleTools(); }
    private static String bounded(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end) + "\n[Display truncated]";
    }
    private synchronized void observe() {
        if (closed || runtime == null || !(phase == READY || phase == BUSY || phase == AUTH_REQUIRED || phase == AUTHORIZING)) return;
        if (runtime.alive()) return;
        long version = generation;
        append(SYSTEM, engine.name() + " stopped. An interrupted prompt is not resent automatically.");
        CompletableFuture<Void> stop = quiesce();
        if (++restarts > 3) { state(ERROR, engine.name() + " repeatedly stopped. Retry to reconnect."); return; }
        stop.whenComplete((ignored, failure) -> {
            if (failure != null) {
                synchronized (this) { if (current(version + 1)) state(ERROR, error(failure)); }
                return;
            }
            scheduler.schedule(() -> {
                synchronized (this) {
                    if (!closed && generation == version + 1 && project != null) {
                        long next = ++generation; state(STARTING, "Restarting " + engine.name() + "…");
                        run(installer::installed, installed -> {
                            if (installed.isEmpty()) state(NOT_INSTALLED, "Install " + engine.name());
                            else start(installed.orElseThrow(), next);
                        }, next);
                    }
                }
            }, 1L << restarts, TimeUnit.SECONDS);
        });
    }
    public synchronized CompletableFuture<Void> quiesce() {
        if (phase == BUSY && !prompting) restoreUnsentPrompt(); pendingPrompt = "";
        historyLoading = false; generation++; turn++; promptCancellation.set(true); prompting = false; cancelling = false; connectAfterStart = false; login = null; permissions.clear();
        settleTools(); sessionState.end();
        if (phase != NO_PROJECT && phase != CLOSED) state(STOPPING, "Stopping agent…");
        remember();
        long version = generation;
        CompletableFuture<Void> imported = CompletableFuture.allOf(attachmentTasks.stream().map(task -> task.handle((ignored, failure) -> null)).toArray(CompletableFuture[]::new));
        return CompletableFuture.allOf(stopRuntime(), imported.thenCompose(ignored -> persistence.flush())).whenComplete((ignored, failure) -> {
            synchronized (this) { if (current(version) && failure != null) state(ERROR, error(failure)); }
        });
    }
    /** Keep ownership after a failed close so a repeated project operation can retry cleanup. */
    private CompletableFuture<Void> stopRuntime() {
        if (runtime != null) { retiring = runtime; runtime = null; }
        AgentRuntime prior = retiring;
        CompletableFuture<Void> previous = stopping;
        stopping = previous.handle((ignored, failure) -> null).thenRunAsync(() -> {
            if (prior != null) prior.close();
            synchronized (this) { if (retiring == prior) retiring = null; }
        }, workers);
        return stopping.copy();
    }
    private <T> void run(Callable<T> operation, Consumer<T> success, long version) { run(operation, success, version, failure -> fail(version, failure)); }
    private <T> void run(Callable<T> operation, Consumer<T> success, long version, Consumer<Throwable> rejected) {
        CompletableFuture.supplyAsync(() -> {
            try { return operation.call(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, workers).whenComplete((result, failure) -> {
            synchronized (this) {
                if (!current(version)) return;
                if (failure != null) {
                    if (runtime != null && !runtime.alive() && (phase == READY || phase == BUSY || phase == AUTH_REQUIRED || phase == AUTHORIZING)) observe();
                    else rejected.accept(failure);
                    return;
                }
                try { success.accept(result); }
                catch (Exception error) { fail(version, error); }
            }
        });
    }
    private void fail(long version, Throwable failure) {
        if (!current(version)) return;
        restoreUnsentPrompt(); pendingPrompt = ""; pendingAttachments = List.of();
        generation++; promptCancellation.set(true); permissions.clear(); login = null; prompting = false; cancelling = false;
        connectAfterStart = false; settleTools(); sessionState.end(); stopRuntime();
        state(ERROR, error(failure));
    }
    private boolean current(long version) { return !closed && generation == version; }
    private void state(AgentSnapshot.Phase phase, String message) { this.phase = phase; status = bounded(message == null ? "" : message, 1000); publish(); }
    private void publish() {
        if (scheduled || closed) return; scheduled = true;
        scheduler.schedule(() -> notifications.execute(() -> {
            List<Consumer<AgentSnapshot>> targets; AgentSnapshot snapshot;
            synchronized (this) { scheduled = false; if (closed) return; targets = List.copyOf(listeners); snapshot = snapshot(); }
            for (var listener : targets) listener.accept(snapshot);
        }), 40, TimeUnit.MILLISECONDS);
    }
    private static String error(Throwable failure) {
        String message = "Agent operation failed";
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) message = cause.getMessage();
        return bounded(message, 1000);
    }
    @Override public synchronized void close() { if (closed) return; quiesce(); closed = true; phase = CLOSED; observation.cancel(false); listeners.clear(); }
}
