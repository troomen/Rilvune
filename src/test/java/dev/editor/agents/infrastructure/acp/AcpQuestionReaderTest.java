package dev.editor.agents.infrastructure.acp;

import dev.editor.agents.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.editor.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

class AcpQuestionReaderTest {
    @Test void formsPreserveOpaqueChoicesRequiredFieldsAndTypedConstraints() {
        var question = AcpQuestionReader.read("q", object("mode", "form", "message", "Configure the run", "requestedSchema", object("type", "object", "required", List.of("pm", "count"), "properties", object(
                "pm", object("type", "string", "oneOf", List.of(object("const", "package-bun", "title", "Bun"))),
                "count", object("type", "integer", "minimum", 1, "maximum", 3),
                "tests", object("type", "array", "items", object("anyOf", List.of(object("const", "unit", "title", "Unit tests"))), "maxItems", 1),
                "enabled", object("type", "boolean"), "pm_custom", object("type", "string", "maxLength", 100)))));
        question.validate(Map.of("pm", "package-bun", "count", 2, "tests", List.of("unit"), "enabled", true));
        assertThrows(IllegalArgumentException.class, () -> question.validate(Map.of("count", 2)));
        assertThrows(IllegalArgumentException.class, () -> question.validate(Map.of("pm", "Bun", "count", 2)));
        assertThrows(IllegalArgumentException.class, () -> question.validate(Map.of("pm", "package-bun", "count", 2.5)));
        assertThrows(IllegalArgumentException.class, () -> question.validate(Map.of("pm", "package-bun", "count", 4)));
        assertThrows(IllegalArgumentException.class, () -> question.validate(Map.of("pm", "package-bun", "count", 2, "unadvertised", "value")));
    }
    @Test void patternsUseJsonSchemaSearchSemanticsAndHaveBoundedEvaluation() {
        var question = AcpQuestionReader.read("q", object("mode", "form", "requestedSchema", object("type", "object", "properties", object("value", object("type", "string", "pattern", "part")))));
        question.validate(Map.of("value", "one part of a sentence"));
        var expensive = AcpQuestionReader.read("q", object("mode", "form", "requestedSchema", object("type", "object", "properties", object("value", object("type", "string", "pattern", "(a+)+$")))));
        assertThrows(IllegalArgumentException.class, () -> expensive.validate(Map.of("value", "a".repeat(2000) + "!")));
    }
}
