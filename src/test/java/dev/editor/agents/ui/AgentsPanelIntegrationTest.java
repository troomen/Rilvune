package dev.editor.agents.ui;

import dev.editor.agents.application.AgentService;
import dev.editor.agents.domain.*;
import dev.editor.agents.domain.content.AgentContent;
import dev.editor.platform.theme.*;
import org.junit.jupiter.api.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.editor.agents.domain.AgentSnapshot.Phase.*;

@Tag("ui") @Timeout(25)
class AgentsPanelIntegrationTest {
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final List<Runtime> runtimes = new CopyOnWriteArrayList<>();
    private final AtomicInteger browsers = new AtomicInteger();
    private final AtomicBoolean installed = new AtomicBoolean(), authenticated = new AtomicBoolean(), browserFailure = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> browserResult = new AtomicReference<>();
    private final dev.editor.agents.support.InMemoryAgentChatStateStore chatStore = new dev.editor.agents.support.InMemoryAgentChatStateStore();
    private final Map<String, List<AgentEvent>> histories = new ConcurrentHashMap<>();
    private final AtomicInteger sessionIds = new AtomicInteger();
    private AgentService service;
    private final AtomicReference<CompletableFuture<Void>> preflight = new AtomicReference<>(CompletableFuture.completedFuture(null));
    private AgentsPanel panel;
    private JFrame frame;
    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }
    @AfterEach void close() throws Exception {
        if (service != null) service.quiesce().get(5, TimeUnit.SECONDS);
        edt(() -> { if (panel != null) panel.close(); if (frame != null) frame.dispose(); return null; });
        if (service != null) service.close(); scheduler.shutdownNow(); workers.shutdownNow();
    }
    private void start(boolean existing, boolean signedIn, int width, int height) throws Exception {
        installed.set(existing); authenticated.set(signedIn);
        service = new AgentService(new AgentRuntimeInstaller() {
            public Optional<Path> installed() { return installed.get() ? Optional.of(Path.of("fixture")) : Optional.empty(); }
            public Path install(Consumer<String> progress) { installed.set(true); return Path.of("fixture"); }
        }, (binary, project, provider, events) -> { Runtime runtime = new Runtime(events); runtimes.add(runtime); return runtime; },
                new AgentEngine("fixture", "Fixture Engine"), new AgentProvider("openai", "Codex", "openai/", "ChatGPT"), chatStore, workers, scheduler, SwingUtilities::invokeLater,
                project -> preflight.get(), () -> { });
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install(); panel = new AgentsPanel(service, theme, uri -> { browsers.incrementAndGet(); if (browserResult.get() != null) return browserResult.get(); return browserFailure.get() ? CompletableFuture.failedFuture(new IllegalStateException("No browser")) : CompletableFuture.completedFuture(null); });
            frame = new JFrame("AI Assistant"); frame.setContentPane(panel); frame.setSize(width, height); frame.setVisible(true);
            service.project(Path.of("/projects/editor")); return null;
        });
        phase(existing ? signedIn ? READY : AUTH_REQUIRED : NOT_INSTALLED);
    }
    @Test void installAndBrowserSignInAreOneFlowWithCopyFallbackAndAutomaticTransition() throws Exception {
        browserFailure.set(true); start(false, false, 420, 760);
        click("agents.setup"); await(() -> browsers.get() == 1);
        edt(() -> {
            assertTrue(find("agents.login.copy", JButton.class).isShowing());
            assertFalse(find("agents.composer", JTextArea.class).isShowing());
            assertFalse(find("agents.loginUrl", JTextField.class).isVisible());
            snapshot("agents-sign-in"); return null;
        });
        awaitUi(() -> find("agents.login.feedback", JTextArea.class).getText().contains("Browser could not open"));
        click("agents.login.copy");
        edt(() -> {
            assertEquals(runtimes.getLast().loginUri.toString(), Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor));
            assertTrue(find("agents.login.feedback", JTextArea.class).getText().contains("Link copied")); return null;
        });
        runtimes.getLast().login.complete(null); phase(READY);
        awaitUi(() -> find("agents.composer", JTextArea.class).isShowing() && find("agents.composer", JTextArea.class).isEnabled());
        edt(() -> { panel.applyTheme(EditorTheme.graphite()); assertFalse(find("agents.setupView", JPanel.class).isVisible()); snapshot("agents-welcome"); return null; });
        assertEquals(1, browsers.get());
    }
    @Test void signInCanBeCancelledAndRetriedWithoutReopeningTheOldLink() throws Exception {
        start(true, false, 420, 700); click("agents.setup"); await(() -> browsers.get() == 1);
        click("agents.login.cancel"); phase(AUTH_REQUIRED); assertEquals(1, browsers.get());
        click("agents.setup"); await(() -> browsers.get() == 2);
        runtimes.getLast().login.completeExceptionally(new IllegalStateException("Sign-in expired")); phase(ERROR);
        awaitUi(() -> find("agents.setup", JButton.class).getText().equals("Try again"));
        click("agents.setup"); await(() -> browsers.get() == 3);
        runtimes.getLast().login.complete(null); phase(READY);
    }
    @Test void automaticBrowserOpeningCanBeDisabledWithoutDisablingManualSignIn() throws Exception {
        start(true, false, 420, 760);
        edt(() -> { panel.settings(new AgentSettings(true, false, true, 2000, 4)); return null; });
        click("agents.setup");
        awaitUi(() -> find("agents.login.open", JButton.class).isEnabled());
        assertEquals(0, browsers.get());
        click("agents.login.open"); await(() -> browsers.get() == 1);
        runtimes.getLast().login.complete(null); phase(READY);
    }

    @Test void messageShortcutChangesApplyToTheCurrentComposer() throws Exception {
        start(true, true, 500, 860);
        edt(() -> { panel.settings(new AgentSettings(false, true, true, 2000, 4)); return null; });
        type("Review the project"); key("ENTER");
        edt(() -> {
            assertEquals(READY, service.snapshot().phase());
            assertTrue(find("agents.composer", JTextArea.class).getText().contains("\n"));
            assertTrue(find("agents.composer.hint", JTextArea.class).getText().contains("Ctrl+Enter"));
            return null;
        });
        key("ctrl ENTER"); phase(BUSY);
    }
    @Test void delayedBrowserFailureCannotChangeAnAlreadyCancelledSignIn() throws Exception {
        var opening = new CompletableFuture<Void>(); browserResult.set(opening);
        start(true, false, 420, 700); click("agents.setup"); await(() -> browsers.get() == 1);
        click("agents.login.cancel"); phase(AUTH_REQUIRED);
        awaitUi(() -> find("agents.setup.description", JTextArea.class).getText().contains("connection is saved"));
        opening.completeExceptionally(new IllegalStateException("Late browser failure"));
        edt(() -> { assertEquals("", find("agents.login.feedback", JTextArea.class).getText()); return null; });
        browserResult.set(CompletableFuture.completedFuture(null)); click("agents.setup"); await(() -> browsers.get() == 2);
        runtimes.getLast().login.complete(null); phase(READY);
    }
    @Test void conversationHasTypedActivityApprovalsDraftingAndARealNewChat() throws Exception {
        start(true, true, 500, 860); type("Review the project"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Text("## Project review\nI will inspect **the tests** and `build.gradle.kts`.\n"));
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("tool", "Run project tests", AgentToolCall.Status.RUNNING, "./gradlew test", "Compiling tests…")));
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("permission", "Run project tests", "./gradlew test", List.of(
                new AgentPermission.Option("once", "Allow once", "allow_once"), new AgentPermission.Option("reject", "Reject", "reject_once")))));
        awaitUi(() -> find("agents.permission.allow_once", JButton.class).isShowing());
        type("Next question"); key("ENTER");
        edt(() -> {
            assertTrue(find("agents.composer", JTextArea.class).isEnabled()); assertEquals("Next question", find("agents.composer", JTextArea.class).getText());
            assertFalse(find("agents.newChat", JButton.class).isEnabled()); assertTrue(find("agents.stop", JButton.class).isShowing());
            JButton stop = find("agents.stop", JButton.class);
            assertTrue(stop.getText() == null || stop.getText().isEmpty());
            assertEquals(new Dimension(28, 28), stop.getPreferredSize());
            var stopIcon = assertInstanceOf(dev.editor.platform.ui.Glyph.class, stop.getIcon());
            assertTrue(stopIcon.color().getRed() > stopIcon.color().getGreen() + 80);
            assertTrue(find("agents.permission.summary", JTextArea.class).getText().contains("./gradlew test"));
            assertFalse(find("agents.permission.details.toggle", JToggleButton.class).isVisible()); snapshot("agents-approval"); return null;
        });
        click("agents.permission.allow_once"); await(() -> runtime.approvals.get() == 1);
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("tool", "Run project tests", AgentToolCall.Status.COMPLETED, "./gradlew test", "All tests passed.")));
        runtime.events.accept(new AgentEvent.Text("The tests passed.\n\n```java\nassertEquals(expected, actual);\n```\n\nReady for the next change."));
        runtime.response.complete(null); phase(READY);
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("Ready for the next change")));
        click("agents.activity");
        click("agents.tool.details");
        edt(() -> {
            assertEquals("Next question", find("agents.composer", JTextArea.class).getText());
            assertTrue(all(panel, JTextPane.class).stream().anyMatch(text -> text.isShowing() && text.getText().contains("All tests passed")));
            assertTrue(all(panel, JPanel.class).stream().noneMatch(view -> "agents.interactions".equals(view.getName()) && view.isShowing()));
            assertFalse(find("agents.stop", JButton.class).isShowing()); snapshot("agents-panel"); return null;
        });
        key("shift ENTER"); edt(() -> { assertTrue(find("agents.composer", JTextArea.class).getText().contains("\n")); return null; });
        click("agents.newChat"); await(() -> runtime.sessions.get() == 2 && service.snapshot().phase() == READY);
        assertTrue(service.snapshot().messages().isEmpty()); assertEquals(1, runtimes.size());
        awaitUi(() -> find("agents.composer", JTextArea.class).getText().isEmpty());
    }
    @Test void streamingDoesNotStealScrollOrSelectionAndNarrowLayoutStillWraps() throws Exception {
        start(true, true, 380, 660); type("Inspect the files"); key("ctrl ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        for (int index = 0; index < 12; index++) {
            runtime.events.accept(new AgentEvent.Text("Inspection " + index + ": a longer explanation that should wrap inside this narrow panel.\n"));
            runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("t" + index, "Inspect source file " + index, AgentToolCall.Status.COMPLETED, "read src/main/java/Example.java", "Read successfully")));
        }
        runtime.events.accept(new AgentEvent.Text("Final reply."));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().equals("Final reply.")));
        edt(() -> { find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().setValue(0); return null; });
        awaitUi(() -> find("agents.latest", JButton.class).isVisible());
        AtomicReference<JTextPane> first = new AtomicReference<>();
        edt(() -> { first.set(all(panel, JTextPane.class).stream().filter(text -> text.getText().contains("Inspection 0")).findFirst().orElseThrow()); first.get().select(0, 10); return null; });
        runtime.events.accept(new AgentEvent.Text(" Another streamed sentence."));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("Another streamed sentence")));
        edt(() -> {
            assertEquals(0, find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().getValue());
            assertEquals(10, first.get().getSelectionEnd());
            frame.setSize(320, 660); panel.applyTheme(EditorTheme.from(dev.editor.settings.domain.EditorSettings.defaults().withFonts("Hack", 16, "Hack", 18))); return null;
        });
        awaitUi(() -> first.get().getWidth() > 0 && first.get().getWidth() < 320 && first.get().getHeight() > 30);
        edt(() -> { assertEquals(16, first.get().getFont().getSize()); assertEquals(find("agents.model", JButton.class).getPreferredSize().width, find("agents.model", JButton.class).getWidth()); snapshot("agents-chat-narrow"); return null; });
        click("agents.latest"); awaitUi(() -> {
            JScrollBar bar = find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar(); return bar.getMaximum() - bar.getVisibleAmount() - bar.getValue() < 28;
        });
    }
    @Test void sendingFromOlderMessagesFollowsTheNewLayoutAndKeepsFollowingAfterResize() throws Exception {
        start(true, true, 380, 660); type("First question"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Text("A detailed explanation that wraps across the narrow conversation.\n".repeat(80)));
        runtime.response.complete(null); phase(READY);
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("detailed explanation")));
        edt(() -> { find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().setValue(0); return null; });
        awaitUi(() -> find("agents.latest", JButton.class).isVisible());
        var saving = new CompletableFuture<Void>(); preflight.set(saving);
        try {
            type("Follow-up question at the bottom"); key("ENTER"); phase(BUSY);
            awaitUi(() -> conversationAtLatest() && all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().equals("Follow-up question at the bottom") && text.getVisibleRect().height > 0));
            edt(() -> { frame.setSize(340, 540); frame.validate(); return null; });
            awaitUi(this::conversationAtLatest);
            edt(() -> { assertFalse(find("agents.latest", JButton.class).isVisible()); return null; });
        } finally { saving.complete(null); }
    }
    @Test void openingOlderActionsRevealsThemWithoutJumpingToTheLaterAnswer() throws Exception {
        start(true, true, 380, 660); type("Inspect the project"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        for (int index = 0; index < 70; index++) runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("read-" + index, "Read " + index, AgentToolCall.Status.COMPLETED, "", "")));
        runtime.events.accept(new AgentEvent.Text("A long conclusion after the actions.\n".repeat(80)));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("long conclusion")));
        edt(() -> { find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().setValue(0); return null; });
        awaitUi(() -> find("agents.latest", JButton.class).isVisible());
        click("agents.activity");
        awaitUi(() -> {
            Container group = (Container)SwingUtilities.getAncestorOfClass(AgentActivityView.class, find("agents.activity", JToggleButton.class));
            JScrollBar bar = all(group, JScrollPane.class).getFirst().getVerticalScrollBar();
            return !conversationAtLatest() && bar.getMaximum() > bar.getVisibleAmount() && bar.getValue() == 0
                    && all(group, JLabel.class).stream().anyMatch(label -> "Read 20".equals(label.getText()) && label.getVisibleRect().height > 0);
        });
    }
    private boolean conversationAtLatest() {
        JScrollBar bar = find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar();
        return bar.getMaximum() - bar.getVisibleAmount() - bar.getValue() < 28;
    }
    @Test void failedResponseKeepsMessagesAndDraftVisibleForRecovery() throws Exception {
        start(true, true, 420, 760); type("Investigate"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Text("This reply is still useful.")); type("Keep my draft");
        runtime.response.completeExceptionally(new IllegalStateException("Connection lost")); phase(ERROR);
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.isShowing() && text.getText().contains("still useful")));
        edt(() -> {
            assertEquals("Keep my draft", find("agents.composer", JTextArea.class).getText());
            assertTrue(find("agents.setup", JButton.class).isShowing()); snapshot("agents-recovery"); return null;
        });
        click("agents.setup"); phase(READY);
        assertTrue(service.snapshot().messages().stream().anyMatch(message -> message.text().contains("still useful")));
    }
    @Test void stoppingKeepsTheDraftAndUnfinishedToolsAreNotShownAsSuccessful() throws Exception {
        start(true, true, 420, 760); type("Run the investigation"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("active", "Inspect files", AgentToolCall.Status.RUNNING, "read files", "")));
        type("Keep writing this question"); click("agents.stop"); phase(READY);
        awaitUi(() -> find("agents.send", JButton.class).isShowing());
        edt(() -> { assertEquals("Keep writing this question", find("agents.composer", JTextArea.class).getText()); return null; });
        assertEquals(AgentToolCall.Status.CANCELLED, ((AgentToolMessage) service.snapshot().messages().getLast()).call().status());
    }
    @Test void savedChatsHaveIndependentDraftsAndTechnicalActivityStaysCollapsed() throws Exception {
        start(true, true, 420, 760); String first = service.snapshot().chatId();
        type("Review the storage design"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        for (int index = 0; index < 100; index++) runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("read-" + index,
                "Read source file " + index, AgentToolCall.Status.COMPLETED, "read src/Example.java", "File contents")));
        runtime.events.accept(new AgentEvent.Text("The storage design is clear. The draft writer is separate from saved agent history."));
        runtime.response.complete(null); phase(READY); service.renameConversation("Storage review");
        awaitUi(() -> find("agents.chats", JButton.class).getText().equals("Storage review"));
        edt(() -> {
            assertEquals(1, all(panel, JToggleButton.class).stream().filter(button -> "agents.activity".equals(button.getName())).count());
            assertFalse(find("agents.activity", JToggleButton.class).isSelected());
            assertEquals("Activity · 100 completed", find("agents.activity", JToggleButton.class).getText());
            assertTrue(all(panel, JToggleButton.class).stream().noneMatch(button -> "agents.tool.details".equals(button.getName())));
            assertFalse(find("agents.attach", JButton.class).isEnabled()); snapshot("agents-multiple-chats"); return null;
        });
        click("agents.activity"); awaitUi(() -> all(panel, JToggleButton.class).stream().filter(button -> "agents.tool.details".equals(button.getName())).count() == 50);
        click("agents.activity");
        click("agents.effort");
        edt(() -> { JTextField search = popupFind("agents.choice.search", JTextField.class); search.setText("High"); search.postActionEvent(); return null; });
        awaitUi(() -> find("agents.effort", JButton.class).getText().equals("High"));
        type("Follow up on storage"); click("agents.newChat");
        awaitUi(() -> !service.snapshot().chatId().equals(first) && find("agents.composer", JTextArea.class).getText().isEmpty());
        String second = service.snapshot().chatId(); type("Draft for the second chat");
        service.renameConversation("Next investigation"); click("agents.chats");
        edt(() -> { snapshot("agents-history", popup()); JTextField search = popupFind("agents.chats.search", JTextField.class); search.setText("Storage"); search.postActionEvent(); return null; });
        awaitUi(() -> service.snapshot().chatId().equals(first) && find("agents.composer", JTextArea.class).getText().equals("Follow up on storage"));
        assertTrue(service.snapshot().messages().stream().anyMatch(message -> message.text().contains("storage design is clear")));
        assertEquals(2, runtime.sessions.get()); // Switching loads a saved session; it does not create one.
        service.switchConversation(second);
        awaitUi(() -> find("agents.composer", JTextArea.class).getText().equals("Draft for the second chat"));
    }
    @Test void reasoningAppearsInChatAndCountedActivityTraceWithIndependentDisclosures() throws Exception {
        start(true, true, 470, 900); type("Inspect this code"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Activity("thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Checking the source", AgentToolCall.Status.RUNNING, AgentOrigin.MAIN, true));
        awaitUi(() -> all(panel, JToggleButton.class).stream().anyMatch(button -> "agents.thinking.details".equals(button.getName()) && button.isShowing()));
        edt(() -> {
            assertFalse(find("agents.thinking.details", JToggleButton.class).isSelected());
            assertNull(SwingUtilities.getAncestorOfClass(AgentActivityView.class, find("agents.thinking.details", JToggleButton.class)));
            return null;
        });
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("read", "Read source", AgentToolCall.Status.RUNNING, "read source", "")));
        awaitUi(() -> all(panel, JToggleButton.class).stream().anyMatch(button -> "agents.activity".equals(button.getName()))
                && all(panel, JTextPane.class).stream().noneMatch(text -> "agents.thinking.text".equals(text.getName()) && text.isShowing()));
        edt(() -> { assertFalse(find("agents.activity", JToggleButton.class).isSelected()); assertEquals(BUSY, service.snapshot().phase()); return null; });
        runtime.events.accept(new AgentEvent.Activity("thought-next", AgentActivityMessage.Kind.REASONING, "Thinking", "Evaluating the result", AgentToolCall.Status.RUNNING, AgentOrigin.MAIN, true));
        awaitUi(() -> all(panel, JToggleButton.class).stream().filter(button -> "agents.thinking.details".equals(button.getName())).count() == 2);
        runtime.response.complete(null); phase(READY);
        awaitUi(() -> all(panel, JTextPane.class).stream().noneMatch(text -> "agents.thinking.text".equals(text.getName()) && text.isShowing()));
        click("agents.activity");
        edt(() -> {
            assertEquals(4, all(panel, JToggleButton.class).stream().filter(button -> "agents.thinking.details".equals(button.getName())).count());
            assertTrue(find("agents.activity", JToggleButton.class).getToolTipText().contains("1 tool action and 2 reasoning steps"));
            assertTrue(all(panel, JToggleButton.class).stream().filter(button -> "agents.thinking.details".equals(button.getName())).noneMatch(AbstractButton::isSelected));
            assertTrue(all(panel, JToggleButton.class).stream().filter(button -> "agents.tool.details".equals(button.getName())).noneMatch(AbstractButton::isSelected));
            find("agents.thinking.details", JToggleButton.class).doClick(); return null;
        });
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> "agents.thinking.text".equals(text.getName()) && text.isShowing() && text.getText().equals("Checking the source")));
        edt(() -> { panel.applyTheme(EditorTheme.graphite()); assertTrue(find("agents.thinking.details", JToggleButton.class).isSelected()); return null; });
    }
    @Test void conversationShowsProgressBeforeOutputAndAfterAnAnswerUntilTheTurnReallyEnds() throws Exception {
        start(true, true, 420, 760); type("Review this project"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        awaitUi(() -> find("agents.turn.progress.title", JLabel.class).isShowing() && conversationAtLatest());
        runtime.events.accept(new AgentEvent.Text("The review is ready."));
        awaitUi(() -> conversationAtLatest() && all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().equals("The review is ready.")));
        edt(() -> {
            JPanel progress = find("agents.turn.progress", JPanel.class);
            assertTrue(progress.isShowing()); assertTrue(progress.getVisibleRect().height > 0);
            assertEquals("Responding…", find("agents.turn.progress.title", JLabel.class).getText());
            assertSame(progress, progress.getParent().getComponent(progress.getParent().getComponentCount() - 1));
            return null;
        });
        runtime.events.accept(new AgentEvent.Progress("retry", new AgentProgress(AgentProgress.Kind.RETRYING, "Retrying in a moment…"), AgentOrigin.MAIN, true));
        awaitUi(() -> find("agents.turn.progress.title", JLabel.class).getToolTipText().equals("Retrying in a moment…"));
        runtime.response.complete(null); phase(READY);
        awaitUi(() -> all(panel, JPanel.class).stream().noneMatch(view -> "agents.turn.progress".equals(view.getName())));
    }
    @Test void richResultsQuestionsTelemetryAndModesRemainVisibleAndStatusSettlesCleanly() throws Exception {
        start(true, true, 470, 900); type("Review the source change"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Activity("thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Inspecting the source and checking the requested change.", AgentToolCall.Status.RUNNING, AgentOrigin.MAIN, true));
        List<AgentContent> content = List.of(new AgentContent.Text("Complete result is visible. " + "long/path/".repeat(50)), new AgentContent.Diff("/projects/editor/Main.java", "class Before {}\n", "class After {}\n"));
        AgentToolCall running = new AgentToolCall("edit", "Update source", AgentToolCall.Status.RUNNING, "{\"path\": \"Main.java\"}", "", "edit", List.of(new AgentLocation("/projects/editor/Main.java", 0)), content, AgentOrigin.MAIN);
        runtime.events.accept(new AgentEvent.Tool(running));
        runtime.events.accept(new AgentEvent.Usage(new AgentUsage(1000, 10000, new java.math.BigDecimal("0.012"), "USD", Map.of())));
        runtime.events.accept(new AgentEvent.Commands(List.of(new AgentCommand("compact", "Compact context", ""))));
        var field = new AgentQuestion.Field("pm", "Package manager", "Choose the tool", AgentQuestion.Type.CHOICE, true, List.of(new AgentQuestion.Choice("opaque-bun", "Bun")), null, null, null, null, null, null, null, "", "");
        runtime.events.accept(new AgentEvent.Question(new AgentQuestion("form", "Which package manager should I use?", List.of(field))));
        awaitUi(() -> find("agents.questions", JPanel.class).isShowing());
        edt(() -> { assertTrue(find("agents.status", JLabel.class).getText().contains("your answer")); assertEquals("Waiting for your answer", find("agents.turn.progress.title", JLabel.class).getText()); assertTrue(find("agents.usage", JButton.class).isVisible()); assertEquals("", find("agents.send", JButton.class).getText()); snapshot("agents-question"); return null; });
        type("Unrelated next message"); key("ENTER"); assertNull(runtime.lastAnswer.get());
        edt(() -> { @SuppressWarnings("unchecked") JComboBox<AgentQuestion.Choice> choices = (JComboBox<AgentQuestion.Choice>)find("agents.question.field.pm", JComboBox.class); choices.setSelectedIndex(0); return null; });
        click("agents.question.reply"); await(() -> runtime.lastAnswer.get() != null);
        assertEquals("opaque-bun", runtime.lastAnswer.get().values().get("pm"));
        runtime.events.accept(new AgentEvent.Tool(running.withStatus(AgentToolCall.Status.COMPLETED)));
        runtime.events.accept(new AgentEvent.Text("The source change is ready for review."));
        runtime.events.accept(new AgentEvent.Finished("end_turn", new AgentUsage(0, 0, java.math.BigDecimal.ZERO, "", Map.of("inputTokens", 45L, "outputTokens", 20L))));
        runtime.response.complete(null); phase(READY); awaitUi(() -> find("agents.status", JLabel.class).getText().equals("Ready"));
        click("agents.activity");
        awaitUi(() -> all(panel, JLabel.class).stream().anyMatch(label -> label.getText().equals("Update source")));
        edt(() -> {
            JLabel title = all(panel, JLabel.class).stream().filter(label -> label.getText().equals("Update source")).findFirst().orElseThrow();
            Container row = SwingUtilities.getAncestorOfClass(AgentMessageView.class, title);
            all(row, JToggleButton.class).getFirst().doClick(); return null;
        });
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("Complete result") && text.getWidth() > 100 && text.getHeight() > 30));
        edt(() -> {
            JTextPane result = all(panel, JTextPane.class).stream().filter(text -> text.getText().contains("Complete result")).findFirst().orElseThrow();
            result.scrollRectToVisible(result.modelToView2D(0).getBounds()); return null;
        });
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("Complete result") && text.getVisibleRect().height > 0));
        awaitUi(() -> SwingUtilities.getAncestorOfClass(AgentActivityView.class, find("agents.activity", JToggleButton.class)).getHeight() >= 250);
        edt(() -> { assertTrue(all(panel, JPanel.class).stream().noneMatch(view -> "agents.questions".equals(view.getName()) && view.isShowing())); snapshot("agents-rich-content"); return null; });
        click("agents.diff.review");
        awaitUi(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        edt(() -> { for (Window window : Window.getWindows()) if (window instanceof dev.editor.diff.ui.SnapshotDiffDialog) window.dispose(); return null; });
        click("agents.usage"); edt(() -> { assertTrue(popupFind("agents.usage.details", JTextArea.class).getText().contains("Context")); popup().setVisible(false); return null; });
        click("agents.options"); edt(() -> { JTextField search = popupFind("agents.choice.search", JTextField.class); search.setText("Plan"); search.postActionEvent(); return null; });
        awaitUi(() -> find("agents.options", JButton.class).getText().equals("Plan"));
        click("agents.commands"); edt(() -> { JTextField search = popupFind("agents.choice.search", JTextField.class); search.setText("compact"); search.postActionEvent(); return null; });
        awaitUi(() -> find("agents.composer", JTextArea.class).getText().equals("/compact "));
    }
    @Test void currentActivityAndToolExpansionsKeepFollowingStreamingOutput() throws Exception {
        start(true, true, 410, 720); type("Inspect the current work"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        for (int index = 0; index < 60; index++) runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("tool-" + index,
                "Read file " + index, AgentToolCall.Status.COMPLETED, "", "Result " + index + "\n".repeat(30))));
        awaitUi(() -> find("agents.activity", JToggleButton.class).getText().contains("60 completed") && conversationAtLatest());
        click("agents.activity");
        awaitUi(() -> {
            Container group = find("agents.activity", JToggleButton.class).getParent().getParent();
            JScrollBar bar = all(group, JScrollPane.class).getFirst().getVerticalScrollBar();
            return conversationAtLatest() && bar.getMaximum() - bar.getVisibleAmount() - bar.getValue() < 28;
        });
        edt(() -> {
            JLabel label = all(panel, JLabel.class).stream().filter(value -> "Read file 59".equals(value.getText())).findFirst().orElseThrow();
            Container row = (Container)SwingUtilities.getAncestorOfClass(AgentMessageView.class, label);
            all(row, JToggleButton.class).getFirst().doClick(); return null;
        });
        awaitUi(this::conversationAtLatest);
        runtime.events.accept(new AgentEvent.Text("The newest answer remains visible.\n".repeat(25)));
        awaitUi(() -> conversationAtLatest() && all(panel, JTextPane.class).stream().anyMatch(value -> value.getText().contains("newest answer") && value.getVisibleRect().height > 0));
    }
    @Test void thinkingAndToolDetailsRevealTheirContentWithoutLosingTheReadingPosition() throws Exception {
        start(true, true, 400, 700); type("Inspect reasoning"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Activity("thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Preserved reasoning\n".repeat(40), AgentToolCall.Status.RUNNING, AgentOrigin.MAIN, true));
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("tool", "Inspect source", AgentToolCall.Status.COMPLETED, "", "Source contents\n".repeat(80))));
        runtime.events.accept(new AgentEvent.Text("A later answer that must not replace the item being read.\n".repeat(80)));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(value -> value.getText().contains("later answer")));
        edt(() -> { find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().setValue(0); return null; });
        awaitUi(() -> find("agents.latest", JButton.class).isVisible()); click("agents.activity"); click("agents.thinking.details");
        awaitUi(() -> !conversationAtLatest() && all(panel, JTextPane.class).stream().anyMatch(value -> "agents.thinking.text".equals(value.getName()) && value.getVisibleRect().height > 0));
        click("agents.tool.details");
        awaitUi(() -> !conversationAtLatest() && all(panel, JTextPane.class).stream().anyMatch(value -> value.getText().contains("Source contents") && value.getVisibleRect().height > 0));
        int readingPosition = edt(() -> find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().getValue());
        runtime.events.accept(new AgentEvent.Progress("retry", new AgentProgress(AgentProgress.Kind.RETRYING, "Retrying while keeping the previous output…"), AgentOrigin.MAIN, true));
        awaitUi(() -> find("agents.turn.progress.title", JLabel.class).getToolTipText().equals("Retrying while keeping the previous output…"));
        edt(() -> { assertEquals(readingPosition, find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().getValue()); return null; });
        runtime.events.accept(new AgentEvent.Text(" Another streamed sentence."));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(value -> value.getText().endsWith("Another streamed sentence.")));
        edt(() -> { assertEquals(readingPosition, find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().getValue()); return null; });
    }
    @Test void approvalDetailsHaveIndependentScrollAndKeepDisclosureStateAcrossTheQueue() throws Exception {
        start(true, true, 420, 780); type("Review actions"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        var options = List.of(new AgentPermission.Option("once", "Allow once", "allow_once"), new AgentPermission.Option("reject", "Reject", "reject_once"));
        String payload = "Complete supplied input\n".repeat(100);
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("first", "Run source checks", payload, options)));
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("second", "Check another source", payload, options)));
        awaitUi(() -> find("agents.interaction.heading", JLabel.class).getText().contains("1 of 2"));
        assertFalse(edt(() -> find("agents.permission.details.toggle", JToggleButton.class).isSelected()));
        int divider = edt(() -> find("agents.chat.split", JSplitPane.class).getDividerLocation());
        int conversationPosition = edt(() -> find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().getValue());
        click("agents.permission.details.toggle");
        awaitUi(() -> find("agents.permission.details", JTextArea.class).getVisibleRect().height > 0);
        edt(() -> {
            assertEquals(payload, find("agents.permission.details", JTextArea.class).getText().substring("Input\n".length()));
            assertEquals(divider, find("agents.chat.split", JSplitPane.class).getDividerLocation());
            assertEquals(conversationPosition, find("agents.conversation.scroll", JScrollPane.class).getVerticalScrollBar().getValue()); return null;
        });
        click("agents.interaction.next");
        edt(() -> { assertFalse(find("agents.permission.details.toggle", JToggleButton.class).isSelected()); return null; });
        click("agents.interaction.previous");
        edt(() -> {
            assertTrue(find("agents.permission.details.toggle", JToggleButton.class).isSelected()); panel.applyTheme(EditorTheme.graphite());
            assertTrue(find("agents.permission.details.toggle", JToggleButton.class).isSelected()); return null;
        });
        assertEquals(0, runtime.approvals.get());
    }
    @Test void queuedExpansionYieldsToScrollingInsideTheActivityTrace() throws Exception {
        start(true, true, 400, 700); type("Inspect outputs"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        for (int index = 0; index < 20; index++) runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("read-" + index, "Read " + index, AgentToolCall.Status.COMPLETED, "", "Source output\n".repeat(60))));
        awaitUi(() -> find("agents.activity", JToggleButton.class).getText().contains("20 completed")); click("agents.activity");
        awaitUi(this::conversationAtLatest);
        edt(() -> {
            Container group = find("agents.activity", JToggleButton.class).getParent().getParent();
            all(group, JToggleButton.class).stream().filter(button -> "agents.tool.details".equals(button.getName())).reduce((first, second) -> second).orElseThrow().doClick();
            frame.validate();
            JScrollPane trace = all(group, JScrollPane.class).getFirst(); trace.getVerticalScrollBar().setValue(0);
            trace.dispatchEvent(new java.awt.event.MouseWheelEvent(trace, java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 10, 10, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1));
            return null;
        });
        awaitUi(() -> find("agents.latest", JButton.class).isVisible());
        runtime.events.accept(new AgentEvent.Text("New response while reading the trace.\n".repeat(30)));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(value -> value.getText().contains("New response")));
        edt(() -> {
            Container group = find("agents.activity", JToggleButton.class).getParent().getParent();
            assertEquals(0, all(group, JScrollPane.class).getFirst().getVerticalScrollBar().getValue());
            assertFalse(conversationAtLatest()); return null;
        });
    }
    @Test void approvalFileListStaysCompactAndDetailsCannotScrollTheNextRequest() throws Exception {
        start(true, true, 500, 920); type("Review proposed changes"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        String input = "Update the source and configuration\n" + "Full action parameters\n".repeat(100);
        var tool = new AgentToolCall("change", "Update application configuration", AgentToolCall.Status.PENDING, input, "", "edit",
                List.of(new AgentLocation("src/Config.java", 2), new AgentLocation("config/settings.json", 0)),
                List.of(new AgentContent.Diff("src/Config.java", "class Config {}", "class Config { int limit; }"),
                        new AgentContent.Diff("src/Config.java", "int limit;", "int limit = 10;")), AgentOrigin.MAIN);
        var options = List.of(new AgentPermission.Option("once", "Allow once", "allow_once"), new AgentPermission.Option("reject", "Reject", "reject_once"));
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("first", tool.title(), input, options, tool)));
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("second", "Run checks", input, options)));
        awaitUi(() -> find("agents.permission.files.heading", JLabel.class).getText().equals("Files · 2"));
        edt(() -> {
            assertFalse(find("agents.permission.details.toggle", JToggleButton.class).isSelected());
            assertEquals(2, all(panel, JPanel.class).stream().filter(row -> "agents.permission.file".equals(row.getName())).count());
            assertEquals(2, all(panel, JButton.class).stream().filter(button -> "agents.diff.review".equals(button.getName())).count());
            assertTrue(all(panel, JLabel.class).stream().anyMatch(label -> "Diff unavailable".equals(label.getText())));
            assertTrue(all(panel, JTabbedPane.class).isEmpty(), "Approval files do not embed full diff editors");
            snapshot("agents-approval-files");
            find("agents.permission.details.toggle", JToggleButton.class).doClick(); find("agents.interaction.next", JButton.class).doClick();
            return null;
        });
        awaitUi(() -> find("agents.permission.title", JLabel.class).getText().equals("Run checks"));
        edt(() -> {
            assertFalse(find("agents.permission.details.toggle", JToggleButton.class).isSelected());
            assertEquals(0, all(find("agents.permissions", JPanel.class), JScrollPane.class).getFirst().getVerticalScrollBar().getValue());
            frame.setSize(340, 620); return null;
        });
        awaitUi(() -> find("agents.composer", JTextArea.class).getWidth() < 340);
        edt(() -> { assertInteractionGeometry(); return null; }); assertEquals(0, runtime.approvals.get());
    }
    @Test void usageIsDiscoverableBeforeStatisticsArriveAndUpdatesWithoutHiding() throws Exception {
        start(true, true, 400, 700);
        awaitUi(() -> find("agents.usage", JButton.class).isShowing()); click("agents.usage");
        edt(() -> { assertTrue(popupFind("agents.usage.details", JTextArea.class).getText().contains("not been reported")); popup().setVisible(false); return null; });
        Runtime runtime = runtimes.getLast();
        runtime.events.accept(new AgentEvent.Usage(new AgentUsage(1000, 10000, java.math.BigDecimal.ZERO, "", Map.of("inputTokens", 200L))));
        awaitUi(() -> find("agents.usage", JButton.class).getText().equals("10%")); click("agents.usage");
        edt(() -> { assertTrue(popupFind("agents.usage.details", JTextArea.class).getText().contains("200")); popup().setVisible(false); return null; });
    }
    @Test void approvalContainsProvidedDiffBeforeAnyPermissionIsGranted() throws Exception {
        start(true, true, 500, 900); type("Inspect a proposed edit"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        AgentToolCall proposed = new AgentToolCall("change", "Change Main.java", AgentToolCall.Status.PENDING, "write Main.java", "", "edit", List.of(),
                List.of(new AgentContent.Diff("/projects/editor/Main.java", "original content", "proposed content")), AgentOrigin.MAIN);
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("approval", proposed.title(), proposed.input(), List.of(new AgentPermission.Option("once", "Allow once", "allow_once")), proposed)));
        awaitUi(() -> find("agents.diff.review", JButton.class).isShowing()); assertEquals(0, runtime.approvals.get());
        click("agents.diff.review");
        awaitUi(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        edt(() -> {
            var dialog = Arrays.stream(Window.getWindows()).filter(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()).map(JDialog.class::cast).findFirst().orElseThrow();
            assertTrue(all(dialog, JTextArea.class).stream().anyMatch(text -> text.getText().equals("original content")));
            assertTrue(all(dialog, JTextArea.class).stream().anyMatch(text -> text.getText().equals("proposed content")));
            JButton close = all(dialog, JButton.class).stream().filter(button -> "diff.snapshot.close".equals(button.getName())).findFirst().orElseThrow();
            assertTrue(close.isShowing()); assertTrue(close.getVisibleRect().height > 0); close.doClick(); return null;
        });
        awaitUi(() -> Arrays.stream(Window.getWindows()).noneMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        click("agents.diff.review");
        awaitUi(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        edt(() -> {
            var dialog = Arrays.stream(Window.getWindows()).filter(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()).map(JDialog.class::cast).findFirst().orElseThrow();
            JRootPane root = dialog.getRootPane(); Object binding = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE"));
            assertNotNull(binding); root.getActionMap().get(binding).actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, binding.toString())); return null;
        });
        awaitUi(() -> Arrays.stream(Window.getWindows()).noneMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        click("agents.diff.review");
        awaitUi(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        edt(() -> {
            Window dialog = Arrays.stream(Window.getWindows()).filter(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()).findFirst().orElseThrow();
            dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING)); return null;
        });
        awaitUi(() -> Arrays.stream(Window.getWindows()).noneMatch(window -> window instanceof dev.editor.diff.ui.SnapshotDiffDialog && window.isShowing()));
        assertEquals(0, runtime.approvals.get()); click("agents.permission.allow_once"); await(() -> runtime.approvals.get() == 1);
        assertEquals("approval:once", runtime.lastPermission.get());
    }
    @Test void pendingRequestsHaveOneBoundedAreaAndApprovalRevealsTheMatchingChildAction() throws Exception {
        start(true, true, 360, 680); type("Review this change"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        runtime.events.accept(new AgentEvent.Text("The worker needs permission to change Main.java."));
        AgentOrigin child = new AgentOrigin("worker", "", 1, "Source reviewer");
        AgentToolCall tool = new AgentToolCall("child:edit", "Update Main.java", AgentToolCall.Status.PENDING,
                "Proposed edit\n".repeat(100), "", "edit", List.of(new AgentLocation("src/Main.java", 4)),
                List.of(new AgentContent.Diff("src/Main.java", "old\n".repeat(200), "new\n".repeat(200))), child);
        runtime.events.accept(new AgentEvent.Tool(tool));
        List<AgentPermission.Option> options = List.of(new AgentPermission.Option("once", "Allow once", "allow_once"),
                new AgentPermission.Option("always", "Always allow", "allow_always"), new AgentPermission.Option("reject", "Reject", "reject_once"));
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("first", tool.title(), tool.input(), options, tool)));
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("second", "Run tests", "./gradlew test", options)));
        awaitUi(() -> find("agents.interaction.heading", JLabel.class).getText().equals("Approval required · 1 of 2"));
        edt(() -> {
            assertTrue(find("agents.permission.context", JTextArea.class).getText().contains("Source reviewer"));
            JPanel file = all(panel, JPanel.class).stream().filter(row -> "src/Main.java".equals(row.getClientProperty("agents.permission.file.path"))).findFirst().orElseThrow();
            assertTrue(all(file, JLabel.class).stream().anyMatch(label -> "Location: src/Main.java:5".equals(label.getToolTipText())));
            assertEquals(1, all(panel, JLabel.class).stream().filter(view -> "agents.permission.title".equals(view.getName()) && view.isShowing()).count());
            assertInteractionGeometry(); return null;
        });
        click("agents.permission.activity");
        awaitUi(() -> all(panel, JLabel.class).stream().anyMatch(label -> "Approval".equals(label.getText()) && label.isShowing() && label.getVisibleRect().height > 0));
        edt(() -> {
            assertTrue(find("agents.activity", JToggleButton.class).isSelected());
            assertTrue(find("agents.activity.child", JToggleButton.class).isSelected());
            assertTrue(find("agents.tool.details", JToggleButton.class).isSelected());
            frame.setSize(340, 620); return null;
        });
        awaitUi(() -> frame.getWidth() == 340 && find("agents.composer", JTextArea.class).getWidth() < 340);
        click("agents.permission.activity");
        awaitUi(() -> all(panel, JLabel.class).stream().anyMatch(label -> "Approval".equals(label.getText()) && label.isShowing() && label.getVisibleRect().height == label.getHeight()));
        edt(() -> { assertInteractionGeometry(); snapshot("agents-approval-queue"); return null; });
        click("agents.interaction.next");
        awaitUi(() -> find("agents.permission.title", JLabel.class).getText().equals("Run tests"));
        assertEquals(0, runtime.approvals.get()); click("agents.permission.allow_once"); await(() -> runtime.approvals.get() == 1);
        assertEquals("second:once", runtime.lastPermission.get());
        awaitUi(() -> find("agents.permission.title", JLabel.class).getText().equals("Update Main.java"));
        edt(() -> { frame.setSize(360, 760); panel.applyTheme(EditorTheme.from(dev.editor.settings.domain.EditorSettings.defaults().withFonts("Hack", 18, "Hack", 18))); return null; });
        awaitUi(() -> find("agents.permission.title", JLabel.class).getFont().getSize() == 18);
        edt(() -> { assertInteractionGeometry(); return null; });
        click("agents.permission.reject_once"); await(() -> runtime.approvals.get() == 2);
        assertEquals("first:reject", runtime.lastPermission.get());
        awaitUi(() -> all(panel, JPanel.class).stream().noneMatch(view -> "agents.interactions".equals(view.getName()) && view.isShowing()));
        edt(() -> { assertTrue(find("agents.conversation", JPanel.class).getHeight() > 200); return null; });
    }
    @Test void switchingBetweenQuestionsAndApprovalsPreservesEditedAnswersAndDoesNotResetTheDivider() throws Exception {
        start(true, true, 430, 780); type("Investigate"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        var field = new AgentQuestion.Field("answer", "Your preference", "", AgentQuestion.Type.TEXT, true, List.of(), null, null, null, null, null, null, null, "", "");
        runtime.events.accept(new AgentEvent.Permission(new AgentPermission("permission", "Run tests", "./gradlew test", List.of(new AgentPermission.Option("once", "Allow once", "allow_once")))));
        runtime.events.accept(new AgentEvent.Question(new AgentQuestion("question", "Which tests should I run?", List.of(field))));
        awaitUi(() -> find("agents.interaction.heading", JLabel.class).getText().contains("1 of 2"));
        click("agents.interaction.next");
        edt(() -> { find("agents.question.field.answer", JTextField.class).setText("Only storage tests"); find("agents.chat.split", JSplitPane.class).setDividerLocation(110); return null; });
        click("agents.interaction.previous"); click("agents.interaction.next");
        edt(() -> { assertEquals("Only storage tests", find("agents.question.field.answer", JTextField.class).getText()); return null; });
        runtime.events.accept(new AgentEvent.Text("A streaming update should preserve the pending form."));
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(view -> view.getText().contains("streaming update")));
        edt(() -> {
            assertEquals(110, find("agents.chat.split", JSplitPane.class).getDividerLocation());
            assertEquals("Only storage tests", find("agents.question.field.answer", JTextField.class).getText());
            assertInteractionGeometry(); return null;
        });
        assertNull(runtime.lastAnswer.get()); click("agents.question.reply"); await(() -> runtime.lastAnswer.get() != null);
        assertEquals("Only storage tests", runtime.lastAnswer.get().values().get("answer"));
        awaitUi(() -> find("agents.interaction.heading", JLabel.class).getText().equals("Approval required"));
        click("agents.stop"); phase(READY); awaitUi(() -> all(panel, JPanel.class).stream().noneMatch(view -> "agents.interactions".equals(view.getName()) && view.isShowing()));
    }
    private void assertInteractionGeometry() {
        JComponent interaction = find("agents.interactions", JPanel.class), composer = find("agents.composerView", JPanel.class), conversation = find("agents.conversation", JPanel.class);
        Rectangle requestBounds = SwingUtilities.convertRectangle(interaction.getParent(), interaction.getBounds(), panel);
        Rectangle composerBounds = SwingUtilities.convertRectangle(composer.getParent(), composer.getBounds(), panel);
        Rectangle conversationBounds = SwingUtilities.convertRectangle(conversation.getParent(), conversation.getBounds(), panel);
        assertTrue(conversationBounds.height >= 55, "Conversation remains visible");
        assertTrue(conversationBounds.y + conversationBounds.height <= requestBounds.y, "Conversation and request do not overlap");
        assertTrue(requestBounds.y + requestBounds.height <= composerBounds.y, "Request and composer do not overlap");
        JScrollPane preview = all(interaction, JScrollPane.class).getFirst();
        assertTrue(preview.getViewport().getHeight() >= 70, "Request preview retains useful space");
        for (JButton button : all(interaction, JButton.class)) if (button.isShowing() && (Objects.toString(button.getName(), "").startsWith("agents.permission.") || "agents.question.reply".equals(button.getName()))) {
            assertEquals(button.getHeight(), button.getVisibleRect().height, button.getName() + " stays fully visible");
            assertTrue(button.getHeight() >= button.getPreferredSize().height, button.getName() + " retains its height");
        }
    }
    @Test void embeddedImagesRenderAndChildRepliesStayInsideTheirOwnActivityGroup() throws Exception {
        start(true, true, 500, 900); type("Inspect the image"); key("ENTER"); phase(BUSY);
        Runtime runtime = runtimes.getLast(); runtime.started.get(5, TimeUnit.SECONDS);
        BufferedImage source = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB); source.setRGB(4, 4, 0xffffff);
        var encoded = new java.io.ByteArrayOutputStream(); ImageIO.write(source, "png", encoded); source.flush();
        runtime.events.accept(new AgentEvent.Content(new AgentContent.Media("image", "image/png", Base64.getEncoder().encodeToString(encoded.toByteArray()), ""), AgentMessage.Role.ASSISTANT, AgentOrigin.MAIN));
        AgentOrigin origin = new AgentOrigin("child", "main", 1, "Explore image handling");
        runtime.events.accept(new AgentEvent.Text("Child-only conclusion", AgentMessage.Role.ASSISTANT, "child-message", origin));
        runtime.events.accept(new AgentEvent.Tool(new AgentToolCall("child:read", "Read image metadata", AgentToolCall.Status.COMPLETED, "read metadata", "12 by 8", "read", List.of(), List.of(), origin)));
        AgentOrigin nested = new AgentOrigin("grandchild", "child", 2, "Nested review");
        runtime.events.accept(new AgentEvent.Text("Nested agent conclusion", AgentMessage.Role.ASSISTANT, "nested-message", nested));
        runtime.events.accept(new AgentEvent.Text("Main conclusion: image inspection finished.")); runtime.response.complete(null); phase(READY);
        awaitUi(() -> find("agents.content.image", JLabel.class).getIcon() != null);
        edt(() -> {
            assertTrue(all(panel, JTextPane.class).stream().noneMatch(text -> text.getText().contains("Child-only conclusion")));
            assertTrue(find("agents.content.image", JLabel.class).getIcon().getIconWidth() > 0); return null;
        });
        click("agents.activity"); click("agents.activity.child");
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("Child-only conclusion") && text.isShowing()));
        awaitUi(() -> all(panel, JToggleButton.class).stream().anyMatch(button -> "agents.activity.child".equals(button.getName()) && button.getText().startsWith("Nested review")));
        edt(() -> { all(panel, JToggleButton.class).stream().filter(button -> "agents.activity.child".equals(button.getName()) && button.getText().startsWith("Nested review")).findFirst().orElseThrow().doClick(); return null; });
        awaitUi(() -> all(panel, JTextPane.class).stream().anyMatch(text -> text.getText().contains("Nested agent conclusion") && text.isShowing()));
        edt(() -> { snapshot("agents-child-image"); return null; });
    }
    @Test void unsentPromptIsRestoredAfterPreparationFailureAndNewerTypingSurvivesQueuedSnapshots() throws Exception {
        start(true, true, 470, 760); var first = new CompletableFuture<Void>(); preflight.set(first);
        type("Do not lose this prompt"); key("ENTER"); phase(BUSY);
        awaitUi(() -> find("agents.composer", JTextArea.class).getText().isEmpty());
        first.completeExceptionally(new IllegalStateException("File could not be saved")); phase(READY);
        awaitUi(() -> find("agents.composer", JTextArea.class).getText().equals("Do not lose this prompt"));
        var second = new CompletableFuture<Void>(); preflight.set(second); key("ENTER"); phase(BUSY); type("A newer draft");
        second.completeExceptionally(new IllegalStateException("File could not be saved")); phase(READY);
        awaitUi(() -> find("agents.status", JLabel.class).getText().contains("Message not sent"));
        edt(() -> { assertEquals("A newer draft", find("agents.composer", JTextArea.class).getText()); return null; });
        assertFalse(runtimes.getLast().started.isDone());
    }
    private static JPopupMenu popup() {
        return Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).filter(JPopupMenu.class::isInstance).map(JPopupMenu.class::cast).findFirst().orElseThrow();
    }
    private static <T extends Component> T popupFind(String name, Class<T> type) { return all(popup(), type).stream().filter(component -> name.equals(component.getName())).findFirst().orElseThrow(); }
    private final class Runtime implements AgentRuntime {
        final Consumer<AgentEvent> events;
        final CompletableFuture<Void> login = new CompletableFuture<>(), response = new CompletableFuture<>(), started = new CompletableFuture<>();
        final AtomicInteger approvals = new AtomicInteger(), sessions = new AtomicInteger();
        final URI loginUri = URI.create("https://example.com/authorize?state=" + "x".repeat(1500));
        boolean alive = true;
        private String currentSession;
        private String chosenEffort = "medium", chosenMode = "build";
        final AtomicReference<AgentQuestionAnswer> lastAnswer = new AtomicReference<>();
        final AtomicReference<String> lastPermission = new AtomicReference<>();
        Runtime(Consumer<AgentEvent> events) { this.events = event -> { histories.get(currentSession).add(event); events.accept(event); }; }
        private AgentConfiguration configuration() { return new AgentConfiguration(List.of(new AgentModel("openai/codex", "Codex")), "openai/codex",
                List.of(new AgentEffort("low", "Low"), new AgentEffort("medium", "Medium"), new AgentEffort("high", "High")), chosenEffort,
                List.of(new AgentConfigOption("mode", "Agent", AgentConfigOption.Category.MODE, chosenMode, List.of(new AgentConfigOption.Choice("build", "Build"), new AgentConfigOption.Choice("plan", "Plan"))))); }
        public Setup start() { return new Setup(authenticated.get(), listSessions("")); }
        public AgentLogin beginLogin() { return new AgentLogin(loginUri, "Complete sign-in"); }
        public Setup awaitLogin() throws Exception { login.get(); authenticated.set(true); return new Setup(true, listSessions("")); }
        public AgentSession newSession() {
            sessions.incrementAndGet(); currentSession = "chat-" + sessionIds.incrementAndGet(); histories.put(currentSession, new CopyOnWriteArrayList<>()); return new AgentSession(currentSession, configuration());
        }
        public AgentSession loadSession(String id, Consumer<AgentEvent> replay) {
            if (!histories.containsKey(id)) throw new IllegalStateException("Unknown chat"); currentSession = id; histories.get(id).forEach(replay); return new AgentSession(id, configuration());
        }
        public AgentChatPage listSessions(String cursor) { return new AgentChatPage(histories.keySet().stream().map(id -> new AgentChat(id, "Saved " + id, java.time.Instant.EPOCH)).toList(), ""); }
        public void deleteSession(String id) { histories.remove(id); }
        public AgentConfiguration selectModel(String id) { return configuration(); }
        public AgentConfiguration selectEffort(String id) { chosenEffort = id; return configuration(); }
        public AgentConfiguration selectOption(String id, String value) { chosenMode = value; return configuration(); }
        public void answer(String id, AgentQuestionAnswer answer) { lastAnswer.set(answer); }
        public void prompt(String text, BooleanSupplier cancelled) throws Exception { histories.get(currentSession).add(new AgentEvent.Text(text, AgentMessage.Role.USER, "user")); started.complete(null); response.get(); }
        public void cancel() { response.complete(null); }
        public void permission(String id, String option) { lastPermission.set(id + ":" + option); approvals.incrementAndGet(); }
        public boolean alive() { return alive; }
        public void close() { alive = false; login.completeExceptionally(new IllegalStateException("Closed")); response.complete(null); }
    }
    private void type(String text) throws Exception { awaitUi(() -> find("agents.composer", JTextArea.class).isShowing()); edt(() -> { find("agents.composer", JTextArea.class).setText(text); return null; }); }
    private void key(String key) throws Exception { edt(() -> {
        JTextArea input = find("agents.composer", JTextArea.class); Object binding = input.getInputMap().get(KeyStroke.getKeyStroke(key));
        input.getActionMap().get(binding).actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, binding.toString())); return null;
    }); }
    private void click(String name) throws Exception { awaitUi(() -> find(name, AbstractButton.class).isShowing() && find(name, AbstractButton.class).isEnabled()); edt(() -> { find(name, AbstractButton.class).doClick(); return null; }); }
    private <T extends Component> T find(String name, Class<T> type) { return all(panel, type).stream().filter(component -> name.equals(component.getName())).findFirst().orElseThrow(); }
    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container container) result.addAll(all(container, type)); }
        return result;
    }
    private void snapshot(String name) throws Exception {
        snapshot(name, panel);
    }
    private void snapshot(String name, JComponent component) throws Exception {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); try { component.printAll(graphics); } finally { graphics.dispose(); }
        Path path = Path.of("build/ui-snapshots/" + name + ".png"); Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
    }
    private void phase(AgentSnapshot.Phase phase) throws Exception { await(() -> service.snapshot().phase() == phase); }
    private static void awaitUi(Callable<Boolean> condition) throws Exception { await(() -> { try { return edt(condition); } catch (Exception unavailable) { return false; } }); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        fail("Agent UI did not settle");
    }
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
}
