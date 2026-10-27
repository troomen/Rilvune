package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;
import static dev.rilvune.agents.ui.AgentReasoningViewTest.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTurnProgressViewTest {
    @Test void liveStatusIsLastEvenAfterAnAnswerAndUpdatesWithoutNewMessages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentConversationView(EditorTheme.graphite(), ignored -> { }, workers, new ArrayList<>());
                List<AgentMessage> messages = List.of(new AgentTextMessage(1, AgentMessage.Role.ASSISTANT, "This looks like a final answer."));
                view.render(snapshot(BUSY, messages));
                JPanel progress = named(view, "agents.turn.progress", JPanel.class);
                Container entries = progress.getParent();
                assertSame(progress, entries.getComponent(entries.getComponentCount() - 1));
                assertEquals("Working…", named(view, "agents.turn.progress.title", JLabel.class).getText());
                assertEquals(1, progress.getComponentCount());
                assertNotNull(named(view, "agents.turn.progress.title", JLabel.class).getIcon());
                for (AgentProgress.Kind kind : List.of(AgentProgress.Kind.THINKING, AgentProgress.Kind.RESPONDING, AgentProgress.Kind.TOOL,
                        AgentProgress.Kind.RETRYING, AgentProgress.Kind.COMPACTING)) {
                    view.render(snapshot(BUSY, messages, new AgentProgress(kind, "Updated: " + kind)));
                    assertSame(progress, named(view, "agents.turn.progress", JPanel.class));
                    assertEquals("Updated: " + kind, named(view, "agents.turn.progress.title", JLabel.class).getToolTipText());
                    assertEquals(switch (kind) {
                        case THINKING -> "Thinking…";
                        case RESPONDING -> "Responding…";
                        case RETRYING -> "Retrying…";
                        case COMPACTING -> "Compacting…";
                        default -> "Working…";
                    }, named(view, "agents.turn.progress.title", JLabel.class).getText());
                }
                view.applyTheme(EditorTheme.graphite());
                assertEquals("Updated: COMPACTING", progress.getAccessibleContext().getAccessibleDescription());
                view.render(snapshot(READY, messages));
                assertTrue(all(view, JPanel.class).stream().noneMatch(panel -> "agents.turn.progress".equals(panel.getName())));
                assertEquals(1, entries.getComponentCount());
            }
        });
    }

    @Test void waitingAndStoppingNeverMasqueradeAsCompletionAndTerminalStatesRemoveTheIndicator() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentConversationView(EditorTheme.graphite(), ignored -> { }, workers, new ArrayList<>());
                for (var state : List.of(
                        new AgentProgress(AgentProgress.Kind.APPROVAL, "Approval required"),
                        new AgentProgress(AgentProgress.Kind.QUESTION, "Waiting for your answer"),
                        new AgentProgress(AgentProgress.Kind.STOPPING, "Stopping response…"))) {
                    view.render(snapshot(BUSY, List.of(), state));
                    String title = named(view, "agents.turn.progress.title", JLabel.class).getText();
                    assertEquals(switch (state.kind()) {
                        case APPROVAL -> "Approval needed";
                        case QUESTION -> "Waiting for your answer";
                        default -> "Stopping…";
                    }, title);
                }
                view.render(snapshot(STOPPING, List.of(), new AgentProgress(AgentProgress.Kind.STOPPING, "Closing agent…")));
                assertEquals("Stopping…", named(view, "agents.turn.progress.title", JLabel.class).getText());
                for (AgentSnapshot.Phase phase : List.of(READY, ERROR, CLOSED, NO_PROJECT)) {
                    view.render(snapshot(phase, List.of()));
                    assertTrue(all(view, JPanel.class).stream().noneMatch(panel -> "agents.turn.progress".equals(panel.getName())));
                }
            }
        });
    }
}
