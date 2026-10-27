package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import static dev.editor.agents.domain.AgentToolCall.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentActivitySummaryTest {
    @Test void progressCountsToolsAndReasoningButNotPlanUpdates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentActivityView(EditorTheme.graphite(), workers, new ArrayList<>());
                List<AgentMessage> messages = new ArrayList<>(actions(COMPLETED, RUNNING, PENDING));
                messages.add(new AgentActivityMessage(10, "thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Inspecting", RUNNING, AgentOrigin.MAIN));
                messages.add(new AgentActivityMessage(11, "plan", AgentActivityMessage.Kind.PLAN, "Plan", "Steps", COMPLETED, AgentOrigin.MAIN));
                view.render(messages);
                JToggleButton header = header(view);
                assertEquals("Activity · 1/4 done · Running…", header.getText());
                assertTrue(header.getToolTipText().contains("4 known actions: 1 completed, 2 running, 1 waiting"));
                assertTrue(header.getToolTipText().contains("3 tool actions and 1 reasoning step"));
                assertEquals(header.getToolTipText(), header.getAccessibleContext().getAccessibleDescription());
                assertFalse(header.isSelected());
                view.pendingTools(Set.of("action-2"));
                assertEquals("Activity · 1/4 done · Approval needed", header.getText());
                view.pendingTools(Set.of());
                assertEquals("Activity · 1/4 done · Running…", header.getText());
            }
        });
    }

    @Test void reasoningIsOneActionWhileStreamingAndKeepsItsRealFinalStatus() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var view = new AgentActivityView(EditorTheme.graphite(), workers, new ArrayList<>());
                for (String text : List.of("Inspecting", "Inspecting more", "Inspecting more source")) {
                    view.render(List.of(new AgentActivityMessage(1, "thought", AgentActivityMessage.Kind.REASONING, "Thinking", text, RUNNING, AgentOrigin.MAIN)));
                    assertEquals("Activity · 0/1 done · Running…", header(view).getText());
                }
                for (var status : List.of(COMPLETED, FAILED, CANCELLED)) {
                    view.render(List.of(new AgentActivityMessage(1, "thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Retained", status, AgentOrigin.MAIN)));
                    String label = switch (status) {
                        case COMPLETED -> "completed";
                        case FAILED -> "failed";
                        default -> "stopped";
                    };
                    assertEquals("Activity · 1 " + label, header(view).getText());
                }
            }
        });
    }

    @Test void discoveredActionsGrowTheTotalAndCompletedGroupsUseQuietFinalCounts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                EditorTheme theme = EditorTheme.graphite();
                var view = new AgentActivityView(theme, workers, new ArrayList<>());
                view.render(actions(COMPLETED, RUNNING));
                assertEquals("Activity · 1/2 done · Running…", header(view).getText());
                view.render(actions(COMPLETED, RUNNING, PENDING));
                assertEquals("Activity · 1/3 done · Running…", header(view).getText());
                view.render(actions(COMPLETED, COMPLETED, COMPLETED));
                assertEquals("Activity · 3 completed", header(view).getText());
                assertEquals(theme.muted(), header(view).getForeground());
                view.applyTheme(theme);
                assertEquals("Activity · 3 completed", header(view).getText());
                view.render(actions(COMPLETED));
                assertEquals("Activity · 1 completed", header(view).getText());
                assertTrue(header(view).getToolTipText().startsWith("1 known action:"));
            }
        });
    }

    @Test void failuresAndStoppedActionsAreNeverCountedAsSuccessfulCompletion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                EditorTheme theme = EditorTheme.graphite();
                var view = new AgentActivityView(theme, workers, new ArrayList<>());
                view.render(actions(COMPLETED, FAILED, RUNNING));
                assertEquals("Activity · 1/3 done · 1 failed", header(view).getText());
                assertEquals(AgentUi.errorColor(theme), header(view).getForeground());
                view.render(actions(COMPLETED, COMPLETED, FAILED));
                assertEquals("Activity · 2 completed · 1 failed", header(view).getText());
                view.render(actions(FAILED));
                assertEquals("Activity · 1 failed", header(view).getText());
                view.render(actions(CANCELLED));
                assertEquals("Activity · 1 stopped", header(view).getText());
                view.render(actions(COMPLETED, CANCELLED));
                assertEquals("Activity · 1 completed · 1 stopped", header(view).getText());
                view.render(actions(PENDING));
                assertEquals("Activity · 0/1 done · Waiting…", header(view).getText());
            }
        });
    }

    private static List<AgentMessage> actions(AgentToolCall.Status... statuses) {
        List<AgentMessage> messages = new ArrayList<>();
        for (int index = 0; index < statuses.length; index++) messages.add(new AgentToolMessage(index + 1,
                new AgentToolCall("action-" + index, "Read source", statuses[index], "", "")));
        return messages;
    }
    private static JToggleButton header(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JToggleButton toggle && "agents.activity".equals(toggle.getName())) return toggle;
            if (child instanceof Container container) { JToggleButton found = header(container); if (found != null) return found; }
        }
        return null;
    }
}
