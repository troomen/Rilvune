package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentApprovalPresentationTest {
    @Test void shortCommandIsCompleteWithoutRedundantDetails() {
        var display = AgentApprovalPresentation.from(request("./gradlew test", "./gradlew test", ""));
        assertEquals("./gradlew test", display.summary()); assertEquals("", display.details());
    }
    @Test void longOrMultilineInputKeepsItsCompleteValueInDetails() {
        for (String input : List.of("x".repeat(500), "one\ntwo\nthree", "a".repeat(239) + "😀")) {
            var display = AgentApprovalPresentation.from(request(input, input, "Diagnostic output"));
            assertTrue(display.summary().endsWith("…")); assertTrue(display.summary().length() <= 241);
            assertFalse(Character.isHighSurrogate(display.summary().charAt(display.summary().length() - 2)));
            assertTrue(display.details().contains(input)); assertTrue(display.details().contains("Diagnostic output"));
        }
    }
    @Test void differentSuppliedDescriptionsAndMissingInputRemainExplicit() {
        var display = AgentApprovalPresentation.from(request("Requested action", "Actual input", "Result"));
        assertTrue(display.summary().startsWith("Requested action")); assertTrue(display.details().contains("Actual input"));
        assertEquals("No action parameters were supplied.", AgentApprovalPresentation.from(request("", "", "")).summary());
    }
    private static AgentPermission request(String details, String input, String output) {
        return new AgentPermission("approval", "Run tests", details, List.of(), new AgentToolCall("tool", "Run tests", AgentToolCall.Status.PENDING, input, output));
    }
}
