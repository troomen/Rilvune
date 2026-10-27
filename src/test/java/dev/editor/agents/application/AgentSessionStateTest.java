package dev.editor.agents.application;

import dev.editor.agents.domain.*;
import dev.editor.agents.domain.content.AgentContent;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static dev.editor.agents.domain.AgentSnapshot.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSessionStateTest {
    @Test void statusPrioritizesHumanInteractionAndRetryThenClearsTransientStateAtTurnEnd() {
        var state = new AgentSessionState(); state.begin();
        state.event(new AgentEvent.Progress("compact", new AgentProgress(AgentProgress.Kind.COMPACTING, "Compacting context"), AgentOrigin.MAIN, true));
        state.event(new AgentEvent.Text("Still responding"));
        assertEquals(AgentProgress.Kind.COMPACTING, state.snapshot(BUSY, "", false, false).progress().kind());
        state.event(new AgentEvent.Progress("retry", new AgentProgress(AgentProgress.Kind.RETRYING, "Rate limited"), AgentOrigin.MAIN, true));
        assertEquals(AgentProgress.Kind.RETRYING, state.snapshot(BUSY, "", false, false).progress().kind());
        state.event(new AgentEvent.Question(new AgentQuestion("q", "Proceed?", List.of())));
        assertEquals(AgentProgress.Kind.QUESTION, state.snapshot(BUSY, "", false, false).progress().kind());
        assertEquals(AgentProgress.Kind.APPROVAL, state.snapshot(BUSY, "", false, true).progress().kind());
        assertEquals(AgentProgress.Kind.STOPPING, state.snapshot(BUSY, "Stopping", true, true).progress().kind());
        state.event(new AgentEvent.Usage(new AgentUsage(50, 100, new BigDecimal("0.03"), "USD", Map.of())));
        state.event(new AgentEvent.Finished("end_turn", new AgentUsage(0, 0, BigDecimal.ZERO, "", Map.of("inputTokens", 12L))));
        state.end(); var completed = state.snapshot(READY, "Ready", false, false);
        assertEquals(AgentProgress.Kind.IDLE, completed.progress().kind()); assertTrue(completed.questions().isEmpty());
        assertEquals(50, completed.usage().contextUsed()); assertEquals(12L, completed.usage().tokens().get("inputTokens"));
        assertEquals(new BigDecimal("0.03"), completed.usage().cost());
        state.begin(); assertEquals(AgentProgress.Kind.WAITING, state.snapshot(BUSY, "", false, false).progress().kind());
    }
    @Test void replayAndCompletionKeepRichContentAndNeverMergeDifferentAgentsOrThoughtsIntoReplies() {
        var history = new AgentTranscript(); AgentOrigin child = new AgentOrigin("child", "parent", 1, "Explore");
        history.event(new AgentEvent.Text("Main reply", AgentMessage.Role.ASSISTANT, "main", AgentOrigin.MAIN));
        history.event(new AgentEvent.Text("Child reply", AgentMessage.Role.ASSISTANT, "main", child));
        history.event(new AgentEvent.Activity("thought", AgentActivityMessage.Kind.REASONING, "Thinking", "Reasoning ", AgentToolCall.Status.RUNNING, child, true));
        history.event(new AgentEvent.Activity("thought", AgentActivityMessage.Kind.REASONING, "Thinking", "continued", AgentToolCall.Status.RUNNING, child, true));
        history.event(new AgentEvent.Content(new AgentContent.Diff("file", "before", "after"), AgentMessage.Role.ASSISTANT, AgentOrigin.MAIN));
        history.finish(false);
        assertEquals(4, history.messages().size()); assertEquals("Main reply", history.messages().getFirst().text());
        assertEquals(child, history.messages().get(1).origin()); assertTrue(history.messages().get(1).technical());
        var thought = (AgentActivityMessage)history.messages().get(2); assertEquals("Reasoning continued", thought.text()); assertEquals(AgentToolCall.Status.COMPLETED, thought.status());
        assertInstanceOf(AgentContent.Diff.class, ((AgentContentMessage)history.messages().getLast()).content());
    }
    @Test void encodedMediaParticipatesInTheHistoryBudget() {
        var history = new AgentTranscript();
        for (int index = 0; index < 10; index++) history.event(new AgentEvent.Content(new AgentContent.Media("image", "image/png", "x".repeat(1024 * 1024), ""), AgentMessage.Role.ASSISTANT, AgentOrigin.MAIN));
        assertTrue(history.truncated()); assertTrue(history.messages().size() < 5);
        var references = new AgentTranscript();
        for (int index = 0; index < 10; index++) references.event(new AgentEvent.Content(new AgentContent.Resource("Reference", "x".repeat(1024 * 1024), "text/plain", ""), AgentMessage.Role.ASSISTANT, AgentOrigin.MAIN));
        assertTrue(references.truncated()); assertTrue(references.messages().size() < 5);
    }
}
