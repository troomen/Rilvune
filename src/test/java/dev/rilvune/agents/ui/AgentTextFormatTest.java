package dev.rilvune.agents.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentTextFormatTest {
    @Test void formatsReadableTextAndTreatsCodeAsLiteral() {
        var spans = AgentTextFormat.parse("## Result\n**Passed** and `name`.\n```java\n**literal** <html>\n```\nDone");
        assertTrue(spans.contains(new AgentTextFormat.Span("Result\n", AgentTextFormat.Style.HEADING)));
        assertTrue(spans.contains(new AgentTextFormat.Span("Passed", AgentTextFormat.Style.BOLD)));
        assertTrue(spans.contains(new AgentTextFormat.Span("**literal** <html>\n", AgentTextFormat.Style.CODE)));
        assertEquals("Result\nPassed and name.\njava\n**literal** <html>\nDone", spans.stream().map(AgentTextFormat.Span::text).reduce("", String::concat));
    }
    @Test void streamingDelimitersAndLongerFencesDoNotLoseLiteralText() {
        assertEquals("An unfinished **word", AgentTextFormat.parse("An unfinished **word").stream().map(AgentTextFormat.Span::text).reduce("", String::concat));
        var spans = AgentTextFormat.parse("````\n```\n**still code**\n````\nEnd");
        assertTrue(spans.contains(new AgentTextFormat.Span("```\n", AgentTextFormat.Style.CODE)));
        assertTrue(spans.contains(new AgentTextFormat.Span("**still code**\n", AgentTextFormat.Style.CODE)));
    }
}
