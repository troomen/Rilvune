package dev.rilvune.settings.infrastructure;

import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.agents.domain.AgentSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesSettingsStoreTest {
    @TempDir Path profile;

    @Test
    void preferencesRoundTripUtf8AndEverySupportedOption() throws Exception {
        PropertiesSettingsStore store = new PropertiesSettingsStore(profile);
        var initial = store.load();
        assertEquals(EditorSettings.defaults(), initial.settings());
        assertFalse(Files.exists(store.file()));
        EditorSettings chosen = new EditorSettings("Шрифт: тест", 20, "Hack", 24,
                8, false, true, false, false, false, false, "CRLF",
                new AgentSettings(false, false, false, 500, 2));

        store.save(chosen, initial.revision());

        assertEquals(chosen, new PropertiesSettingsStore(profile).load().settings());
        assertTrue(Files.readString(store.file()).contains("Шрифт"));
        try (var paths = Files.list(profile)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test void missingMinimapPreferenceUsesDefaultWithoutDiscardingOtherPreferences() throws Exception {
        PropertiesSettingsStore store = new PropertiesSettingsStore(profile);
        store.save(EditorSettings.defaults().withFonts("Hack", 20, "Hack", 24), store.load().revision());
        Files.writeString(store.file(), Files.readString(store.file()).replace("editor.minimap=true\n", ""));
        var loaded = store.load();
        assertTrue(loaded.warning().isEmpty()); assertTrue(loaded.settings().showMinimap());
        assertEquals(24, loaded.settings().editorFontSize());
        Files.writeString(store.file(), Files.readString(store.file()) + "editor.minimap=invalid\n");
        assertTrue(store.load().warning().orElseThrow().contains("Invalid boolean"));
    }

    @Test void legacyPreferencesUseAgentDefaultsAndMalformedAgentValuesAreReported() throws Exception {
        var store = new PropertiesSettingsStore(profile);
        store.save(EditorSettings.defaults().withFonts("Hack", 20, "Hack", 24), store.load().revision());
        Files.writeString(store.file(), Files.readString(store.file()).replaceAll("(?m)^agents\\.[^\\n]*\\n", ""));
        var loaded = store.load();
        assertTrue(loaded.warning().isEmpty());
        assertEquals(24, loaded.settings().editorFontSize());
        assertEquals(AgentSettings.defaults(), loaded.settings().agents());
        Files.writeString(store.file(), Files.readString(store.file()) + "agents.display.messages=0\n");
        assertTrue(store.load().warning().orElseThrow().contains("Display message limit"));
    }

    @Test void fontChangesPreserveAgentPreferences() {
        var agents = new AgentSettings(false, false, false, 100, 1);
        assertEquals(agents, EditorSettings.defaults().withAgents(agents).withFonts("Hack", 18, "Hack", 20).agents());
        assertThrows(IllegalArgumentException.class, () -> new AgentSettings(true, true, true, 99, 4));
        assertThrows(IllegalArgumentException.class, () -> new AgentSettings(true, true, true, 2000, 33));
    }

    @Test void syntaxPreferenceDefaultsStrictlyPersistsAndSurvivesAllSettingsCopyOperations() throws Exception {
        var store = new PropertiesSettingsStore(profile);
        var chosen = EditorSettings.defaults().withSyntaxHighlighting(false).withSoftWrap(true)
                .withFonts("Hack", 18, "Hack", 20).withAgents(AgentSettings.defaults());
        assertFalse(chosen.syntaxHighlighting()); store.save(chosen, store.load().revision());
        assertEquals(chosen, store.load().settings());
        Files.writeString(store.file(), Files.readString(store.file()).replace("editor.syntaxHighlighting=false\n", ""));
        assertTrue(store.load().settings().syntaxHighlighting()); assertEquals(20, store.load().settings().editorFontSize());
        Files.writeString(store.file(), Files.readString(store.file()) + "editor.syntaxHighlighting=tru\n");
        assertTrue(store.load().warning().orElseThrow().contains("Invalid boolean"));
    }

    @Test void globalWordWrapPersistsWithoutChangingOtherPreferences() throws Exception {
        var store = new PropertiesSettingsStore(profile);
        var original = EditorSettings.defaults().withFonts("Hack", 18, "Hack", 22)
                .withAgents(new AgentSettings(false, false, false, 500, 2));
        var wrapped = original.withSoftWrap(true);
        store.save(wrapped, store.load().revision());
        assertTrue(new PropertiesSettingsStore(profile).load().settings().softWrap());
        assertEquals(original, new PropertiesSettingsStore(profile).load().settings().withSoftWrap(false));
    }

    @Test
    void staleInstanceCannotReplaceAnotherInstancesPreferences() throws Exception {
        PropertiesSettingsStore first = new PropertiesSettingsStore(profile);
        PropertiesSettingsStore second = new PropertiesSettingsStore(profile);
        var stale = second.load();
        var desired = EditorSettings.defaults().withFonts("Hack", 18, "Hack", 21);
        first.save(desired, first.load().revision());

        IOException failure = assertThrows(IOException.class,
                () -> second.save(EditorSettings.defaults(), stale.revision()));

        assertTrue(failure.getMessage().contains("another editor instance"));
        assertEquals(desired, first.load().settings());
    }

    @Test
    void corruptPreferencesStayUntouchedAndExplicitApplyKeepsOriginalBackup() throws Exception {
        PropertiesSettingsStore store = new PropertiesSettingsStore(profile);
        String malformed = "schema=999\nui.font.size=broken\n";
        Files.writeString(store.file(), malformed);

        var loaded = store.load();

        assertEquals(EditorSettings.defaults(), loaded.settings());
        assertTrue(loaded.warning().isPresent());
        assertEquals(malformed, Files.readString(store.file()));
        store.save(EditorSettings.defaults(), loaded.revision());
        assertTrue(store.load().warning().isEmpty());
        try (var paths = Files.list(profile)) {
            Path backup = paths.filter(path -> path.getFileName().toString().startsWith("settings.invalid-")).findFirst().orElseThrow();
            assertEquals(malformed, Files.readString(backup));
        }
    }

    @Test
    void invalidBooleanIsReportedInsteadOfSilentlyBecomingFalse() throws Exception {
        PropertiesSettingsStore store = new PropertiesSettingsStore(profile);
        store.save(EditorSettings.defaults(), store.load().revision());
        Files.writeString(store.file(), Files.readString(store.file()).replace("editor.use.spaces=true", "editor.use.spaces=tru"));

        assertTrue(store.load().warning().orElseThrow().contains("Invalid boolean"));
    }
}
