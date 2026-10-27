package dev.editor.agents.domain;

/** Global editor preferences, independent of providers, models and saved agent context. */
public record AgentSettings(boolean sendOnEnter, boolean openLoginInBrowser, boolean restoreLastChat,
                            int displayMessageLimit, int displayCharacterLimitMiB) {
    public AgentSettings {
        if (displayMessageLimit < 100 || displayMessageLimit > 10000)
            throw new IllegalArgumentException("Display message limit must be between 100 and 10000");
        if (displayCharacterLimitMiB < 1 || displayCharacterLimitMiB > 32)
            throw new IllegalArgumentException("Display character limit must be between 1 and 32 Mi characters");
    }

    public static AgentSettings defaults() { return new AgentSettings(true, true, true, 2000, 4); }
}
