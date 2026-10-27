package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import static dev.rilvune.agents.domain.AgentToolCall.Status.*;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Chat and activity disclosures project the same reasoning without duplicating stored messages. */
class AgentReasoningViewTest {
    @Test void reasoningStaysInChatCollapsedThroughStreamingCompletionAndReplay() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentConversationView(EditorTheme.graphite(), ignored -> { }, workers, new ArrayList<>());
                view.render(snapshot(BUSY, List.of(thought(RUNNING, "Inspecting"))));
                var disclosure = named(view, "agents.thinking.details", JToggleButton.class);
                assertFalse(disclosure.isSelected());
                assertNull(SwingUtilities.getAncestorOfClass(AgentActivityView.class, disclosure));
                assertTrue(all(view, JLabel.class).stream().anyMatch(label -> "Reasoning".equals(label.getText())));
                assertEquals("Activity · 0/1 done · Running…", named(view, "agents.activity", JToggleButton.class).getText());
                view.render(snapshot(BUSY, List.of(thought(RUNNING, "Inspecting further"))));
                view.render(snapshot(READY, List.of(thought(COMPLETED, "Inspecting further"))));
                assertSame(disclosure, named(view, "agents.thinking.details", JToggleButton.class));
                assertFalse(disclosure.isSelected());
                disclosure.doClick();
                assertEquals("Inspecting further", named(view, "agents.thinking.text", JTextPane.class).getText());
                var replay = new AgentConversationView(EditorTheme.graphite(), ignored -> { }, workers, new ArrayList<>());
                replay.render(snapshot(READY, List.of(thought(COMPLETED, "Saved reasoning"))));
                assertFalse(named(replay, "agents.thinking.details", JToggleButton.class).isSelected());
            }
        });
    }

    @Test void explicitExpansionAndTextSelectionSurviveUpdatesCompletionAndThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentConversationView(EditorTheme.graphite(), ignored -> { }, workers, new ArrayList<>());
                view.render(snapshot(BUSY, List.of(thought(RUNNING, "Inspecting"))));
                var disclosure = named(view, "agents.thinking.details", JToggleButton.class);
                disclosure.doClick();
                var text = named(view, "agents.thinking.text", JTextPane.class); text.select(0, 5);
                view.render(snapshot(BUSY, List.of(thought(RUNNING, "Inspecting further"))));
                assertSame(text, named(view, "agents.thinking.text", JTextPane.class));
                assertEquals("Inspe", text.getSelectedText());
                view.render(snapshot(READY, List.of(thought(COMPLETED, "Inspecting further"))));
                view.applyTheme(EditorTheme.graphite());
                assertTrue(disclosure.isSelected());
                disclosure.doClick();
                view.render(snapshot(READY, List.of(thought(CANCELLED, "Inspecting further"))));
                assertSame(disclosure, named(view, "agents.thinking.details", JToggleButton.class));
                assertFalse(disclosure.isSelected());
            }
        });
    }

    @Test void childReasoningAppearsInBothChatAndActivityAndCountsOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentConversationView(EditorTheme.graphite(), ignored -> { }, workers, new ArrayList<>());
                var child = new AgentOrigin("child", "", 1, "Review");
                List<AgentMessage> messages = List.of(
                        new AgentTextMessage(1, AgentMessage.Role.USER, "Review source"),
                        new AgentActivityMessage(2, "thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Checking", RUNNING, child),
                        new AgentToolMessage(3, new AgentToolCall("read", "Read", COMPLETED, "", "", "read", List.of(), List.of(), child)),
                        new AgentTextMessage(4, AgentMessage.Role.ASSISTANT, "Answer"));
                view.render(snapshot(BUSY, messages));
                var reasoning = named(view, "agents.thinking.details", JToggleButton.class);
                Container reasoningEntry = SwingUtilities.getAncestorOfClass(AgentMessageView.class, reasoning);
                Container entries = reasoningEntry.getParent();
                assertTrue(entries.getComponentZOrder(reasoningEntry) < entries.getComponentZOrder(named(view, "agents.message.4", JPanel.class)));
                named(view, "agents.activity", JToggleButton.class).doClick();
                named(view, "agents.activity.child", JToggleButton.class).doClick();
                assertEquals(2, all(view, JToggleButton.class).stream().filter(button -> "agents.thinking.details".equals(button.getName())).count());
                assertEquals("Activity · 1/2 done · Running…", named(view, "agents.activity", JToggleButton.class).getText());
                assertEquals("Review · 1/2 done · Running…", named(view, "agents.activity.child", JToggleButton.class).getText());
                assertFalse(reasoning.isSelected());
                var traceReasoning = all(view, JToggleButton.class).stream().filter(button -> "agents.thinking.details".equals(button.getName()) && button != reasoning).findFirst().orElseThrow();
                traceReasoning.doClick();
                assertTrue(traceReasoning.isSelected()); assertFalse(reasoning.isSelected());
                assertTrue(all(view, JLabel.class).stream().anyMatch(label -> "Review · Reasoning".equals(label.getText())));
                assertFalse(named(view, "agents.tool.details", JToggleButton.class).isSelected());
                assertSame(reasoning, named(view, "agents.thinking.details", JToggleButton.class));
            }
        });
    }

    @Test void reasoningStatusSharesTheHeaderRowAndTracksCompletionWithoutExtraHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentMessageView(thought(RUNNING, "Working"), "Agent", EditorTheme.graphite(), workers, new ArrayList<>());
                var status = named(view, "agents.message.status", JLabel.class);
                var title = named(view, "agents.message.title", JLabel.class);
                var disclosure = named(view, "agents.thinking.details", JToggleButton.class);
                assertSame(disclosure.getParent(), status.getParent());
                var header = (BorderLayout)title.getParent().getLayout();
                assertNull(header.getLayoutComponent(BorderLayout.SOUTH));
                int height = view.getPreferredSize().height;
                view.render(thought(COMPLETED, "Working"));
                view.applyTheme(EditorTheme.graphite());
                assertEquals("Done", status.getText());
                assertEquals(height, view.getPreferredSize().height);
                assertFalse(disclosure.isSelected());
            }
        });
    }

    static AgentSnapshot snapshot(AgentSnapshot.Phase phase, List<AgentMessage> messages) {
        return snapshot(phase, messages, new AgentProgress(phase == BUSY ? AgentProgress.Kind.WAITING : AgentProgress.Kind.IDLE, phase == BUSY ? "Waiting for the agent…" : "Ready"));
    }

    static AgentSnapshot snapshot(AgentSnapshot.Phase phase, List<AgentMessage> messages, AgentProgress progress) {
        return new AgentSnapshot(Path.of("/project"), phase, progress.detail(), new AgentEngine("fixture", "Fixture"),
                new AgentProvider("fixture", "Agent", "fixture/", "Account"), new AgentSession("chat", AgentConfiguration.empty()),
                null, progress.kind() == AgentProgress.Kind.STOPPING, messages, List.of(), AgentChatPage.empty(), "Chat", "", 0, "", false,
                new AgentSessionDetails(progress, AgentUsage.empty(), List.of(), List.of()), List.of(), false);
    }

    private static AgentActivityMessage thought(AgentToolCall.Status status, String text) {
        return new AgentActivityMessage(1, "thought", AgentActivityMessage.Kind.REASONING, "Thinking", text, status, AgentOrigin.MAIN);
    }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        return all(root, type).stream().filter(component -> name.equals(component.getName())).findFirst().orElseThrow();
    }

    static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(all(container, type));
        }
        return result;
    }
}
