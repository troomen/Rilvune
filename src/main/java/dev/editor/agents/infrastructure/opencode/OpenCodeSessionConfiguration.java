package dev.editor.agents.infrastructure.opencode;
import com.google.gson.JsonObject;
import dev.editor.agents.domain.*;
import dev.editor.agents.infrastructure.acp.AcpConfigurationReader;
import java.io.IOException;

record OpenCodeSessionConfiguration(String modelOption, String effortOption, AgentConfiguration value) {
    static OpenCodeSessionConfiguration read(JsonObject session, AgentProvider provider, String recommended) throws IOException {
        AgentConfiguration value = AgentConfiguration.from(AcpConfigurationReader.read(session), provider.modelPrefix(), recommended);
        if (value.models().isEmpty()) throw new IOException("No " + provider.name() + " models are available. Check your account and OpenCode configuration.");
        return new OpenCodeSessionConfiguration(option(value, AgentConfigOption.Category.MODEL), option(value, AgentConfigOption.Category.EFFORT), value);
    }
    private static String option(AgentConfiguration value, AgentConfigOption.Category category) {
        return value.options().stream().filter(option -> option.category() == category).map(AgentConfigOption::id).findFirst().orElse("");
    }
}
