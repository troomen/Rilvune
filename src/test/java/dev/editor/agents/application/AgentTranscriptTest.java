package dev.editor.agents.application;

import dev.editor.agents.domain.*;
import dev.editor.agents.domain.content.AgentContent;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentTranscriptTest {
    @Test void displayLimitsApplyImmediatelyAndToFutureEntries() {
        var transcript = new AgentTranscript();
        for (int i = 0; i < 150; i++) transcript.append(AgentMessage.Role.USER, "Message " + i);
        transcript.settings(new AgentSettings(true, true, true, 100, 1));
        assertEquals(100, transcript.messages().size());
        assertEquals("Message 50", transcript.messages().getFirst().text());
        assertTrue(transcript.truncated());
        transcript.append(AgentMessage.Role.USER, "Next");
        assertEquals(100, transcript.messages().size());
        assertEquals("Message 51", transcript.messages().getFirst().text());
        for (int i = 0; i < 30; i++) transcript.append(AgentMessage.Role.USER, "x".repeat(60000));
        assertTrue(transcript.messages().stream().mapToLong(AgentMessage::retainedCharacters).sum() <= 1024 * 1024);
    }
    @Test void thinkingFinishesWhenTheSameAgentStartsAToolAndCanResumeWithTheSameKey() {
        var transcript = new AgentTranscript();
        transcript.event(thought("main", "Inspecting", AgentOrigin.MAIN));
        transcript.event(new AgentEvent.Tool(new AgentToolCall("read", "Read source", AgentToolCall.Status.RUNNING, "", "")));
        assertEquals(AgentToolCall.Status.COMPLETED, reasoning(transcript, "main").status());
        long id = reasoning(transcript, "main").id();
        transcript.event(thought("main", " again", AgentOrigin.MAIN));
        assertEquals(id, reasoning(transcript, "main").id());
        assertEquals("Inspecting again", reasoning(transcript, "main").text());
        assertEquals(AgentToolCall.Status.RUNNING, reasoning(transcript, "main").status());
        transcript.event(new AgentEvent.Tool(new AgentToolCall("read", "Read source", AgentToolCall.Status.COMPLETED, "", "")));
        assertEquals(AgentToolCall.Status.RUNNING, reasoning(transcript, "main").status()); // A late tool result is not a new reasoning transition.
        transcript.event(new AgentEvent.Text("Here is the answer"));
        assertEquals(AgentToolCall.Status.COMPLETED, reasoning(transcript, "main").status());
    }

    @Test void childActivityDoesNotFinishParentThinkingAndNewThoughtsFinishOnlyTheirOwnOrigin() {
        var transcript = new AgentTranscript();
        var child = new AgentOrigin("child", "", 1, "Review");
        transcript.event(thought("parent", "Parent", AgentOrigin.MAIN));
        transcript.event(thought("child", "Child", child));
        transcript.event(thought("child-next", "Next", child));
        assertEquals(AgentToolCall.Status.RUNNING, reasoning(transcript, "parent").status());
        assertEquals(AgentToolCall.Status.COMPLETED, reasoning(transcript, "child").status());
        transcript.event(new AgentEvent.Tool(new AgentToolCall("read", "Read", AgentToolCall.Status.RUNNING, "", "", "read", List.of(), List.of(), child)));
        assertEquals(AgentToolCall.Status.COMPLETED, reasoning(transcript, "child-next").status());
        assertEquals(AgentToolCall.Status.RUNNING, reasoning(transcript, "parent").status());
        transcript.event(new AgentEvent.Content(new AgentContent.Text("Answer"), AgentMessage.Role.ASSISTANT, AgentOrigin.MAIN));
        assertEquals(AgentToolCall.Status.COMPLETED, reasoning(transcript, "parent").status());
    }

    @Test void completionAndCancellationSettleThinkingWithoutDiscardingItsText() {
        for (boolean cancelled : List.of(false, true)) {
            var transcript = new AgentTranscript(); transcript.event(thought("main", "Retained", AgentOrigin.MAIN));
            transcript.finish(cancelled);
            assertEquals(cancelled ? AgentToolCall.Status.CANCELLED : AgentToolCall.Status.COMPLETED, reasoning(transcript, "main").status());
            assertEquals("Retained", reasoning(transcript, "main").text());
        }
    }

    @Test void renamedChildrenFinishOnlyTheirOwnReasoningAcrossEveryTransition() {
        var original = new AgentOrigin("child", "", 1, "Explore");
        var renamed = new AgentOrigin("child", "parent", 2, "Review source");
        var sibling = new AgentOrigin("sibling", "", 1, "Explore");
        List<AgentEvent> transitions = List.of(
                new AgentEvent.Text("Answer", AgentMessage.Role.ASSISTANT, "reply", renamed),
                new AgentEvent.Content(new AgentContent.Text("Content"), AgentMessage.Role.ASSISTANT, renamed),
                new AgentEvent.Tool(new AgentToolCall("child:read", "Read", AgentToolCall.Status.RUNNING, "", "", "read", List.of(), List.of(), renamed)),
                thought("next", "Next step", renamed));
        for (AgentEvent transition : transitions) {
            var transcript = new AgentTranscript();
            transcript.event(thought("main", "Parent", AgentOrigin.MAIN));
            transcript.event(thought("sibling", "Sibling", sibling));
            transcript.event(thought("child", "Preserved thought", original));
            long id = reasoning(transcript, "child").id();
            transcript.event(transition);
            assertEquals(AgentToolCall.Status.COMPLETED, reasoning(transcript, "child").status());
            assertEquals(id, reasoning(transcript, "child").id());
            assertEquals("Preserved thought", reasoning(transcript, "child").text());
            assertEquals(AgentToolCall.Status.RUNNING, reasoning(transcript, "main").status());
            assertEquals(AgentToolCall.Status.RUNNING, reasoning(transcript, "sibling").status());
        }
    }
    private static AgentEvent.Activity thought(String key, String text, AgentOrigin origin) {
        return new AgentEvent.Activity(key, AgentActivityMessage.Kind.REASONING, "Thinking", text, AgentToolCall.Status.RUNNING, origin, true);
    }
    private static AgentActivityMessage reasoning(AgentTranscript transcript, String key) {
        return transcript.messages().stream().filter(AgentActivityMessage.class::isInstance).map(AgentActivityMessage.class::cast)
                .filter(message -> message.key().equals(key)).findFirst().orElseThrow();
    }
}
