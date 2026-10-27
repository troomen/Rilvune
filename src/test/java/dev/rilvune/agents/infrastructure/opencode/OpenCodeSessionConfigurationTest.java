package dev.rilvune.agents.infrastructure.opencode;

import com.google.gson.JsonObject;
import dev.rilvune.agents.domain.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static dev.rilvune.agents.infrastructure.AgentJson.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeSessionConfigurationTest {
    private static final AgentProvider CODEX = new AgentProvider("openai", "Codex", "openai/", "ChatGPT");
    private static JsonObject session(String current) {
        return object("configOptions", List.of(object("id", "model-choice", "category", "model", "type", "select", "currentValue", current,
                "options", List.of(object("value", "openai/recommended", "name", "openai/Recommended"),
                        object("value", "openai/other", "name", "openai/Other"), object("value", "another/model", "name", "Another provider")))));
    }
    @Test void currentProviderModelTakesPriorityOverRuntimeRecommendation() throws Exception {
        OpenCodeSessionConfiguration models = OpenCodeSessionConfiguration.read(session("openai/other"), CODEX, "openai/recommended");
        assertEquals("model-choice", models.modelOption()); assertEquals("openai/other", models.value().selectedModel());
        assertEquals(2, models.value().models().size()); assertTrue(models.value().models().stream().allMatch(model -> model.id().startsWith("openai/")));
    }
    @Test void runtimeRecommendationIsUsedInsteadOfAnotherProvidersCurrentModel() throws Exception {
        assertEquals("openai/recommended", OpenCodeSessionConfiguration.read(session("another/model"), CODEX, "openai/recommended").value().selectedModel());
    }
    @Test void missingProviderModelsCannotUnlockChat() {
        assertThrows(IOException.class, () -> OpenCodeSessionConfiguration.read(object(), CODEX, "openai/recommended"));
    }
    @Test void effortIsAdvertisedByTheRuntimeAndDisappearsForModelsWithoutVariants() throws Exception {
        JsonObject result = session("openai/other");
        result.getAsJsonArray("configOptions").add(object("id", "reasoning-choice", "category", "thought_level", "type", "select", "currentValue", "maximum",
                "options", List.of(object("value", "automatic", "name", "Model default"), object("value", "maximum", "name", "Maximum"))));
        var configured = OpenCodeSessionConfiguration.read(result, CODEX, "");
        assertEquals("reasoning-choice", configured.effortOption()); assertEquals("maximum", configured.value().selectedEffort());
        assertEquals(List.of(new AgentEffort("automatic", "Model default"), new AgentEffort("maximum", "Maximum")), configured.value().efforts());
        assertEquals("Other", configured.value().models().stream().filter(model -> model.id().equals("openai/other")).findFirst().orElseThrow().name());
        var unsupported = OpenCodeSessionConfiguration.read(session("openai/other"), CODEX, "");
        assertTrue(unsupported.value().efforts().isEmpty()); assertEquals("", unsupported.effortOption());
    }
}
