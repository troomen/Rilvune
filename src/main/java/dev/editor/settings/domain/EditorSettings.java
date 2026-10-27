package dev.editor.settings.domain;

import java.util.Objects;
import dev.editor.agents.domain.AgentSettings;

/** Persisted preferences, independent of the toolkit and the current project. */
public record EditorSettings(
        String uiFontFamily, int uiFontSize,
        String editorFontFamily, int editorFontSize,
        int tabSize, boolean useSpaces, boolean softWrap,
        boolean showLineNumbers, boolean highlightCurrentLine, boolean showMinimap,
        boolean showHiddenFiles, String defaultLineEnding, AgentSettings agents, boolean syntaxHighlighting) {

    public EditorSettings {
        Objects.requireNonNull(agents, "Agent settings");
        uiFontFamily = requireFamily(uiFontFamily);
        editorFontFamily = requireFamily(editorFontFamily);
        requireRange("Interface font size", uiFontSize, 10, 36);
        requireRange("Editor font size", editorFontSize, 8, 72);
        requireRange("Tab size", tabSize, 1, 16);
        if (!Objects.equals(defaultLineEnding, "LF") && !Objects.equals(defaultLineEnding, "CRLF")) {
            throw new IllegalArgumentException("New file line ending must be LF or CRLF");
        }
    }

    public EditorSettings(String uiFontFamily, int uiFontSize, String editorFontFamily, int editorFontSize,
                          int tabSize, boolean useSpaces, boolean softWrap, boolean showLineNumbers,
                          boolean highlightCurrentLine, boolean showMinimap, boolean showHiddenFiles, String defaultLineEnding, AgentSettings agents) {
        this(uiFontFamily, uiFontSize, editorFontFamily, editorFontSize, tabSize, useSpaces, softWrap, showLineNumbers,
                highlightCurrentLine, showMinimap, showHiddenFiles, defaultLineEnding, agents, true);
    }

    public EditorSettings(String uiFontFamily, int uiFontSize, String editorFontFamily, int editorFontSize,
                          int tabSize, boolean useSpaces, boolean softWrap, boolean showLineNumbers,
                          boolean highlightCurrentLine, boolean showMinimap, boolean showHiddenFiles, String defaultLineEnding) {
        this(uiFontFamily, uiFontSize, editorFontFamily, editorFontSize, tabSize, useSpaces, softWrap,
                showLineNumbers, highlightCurrentLine, showMinimap, showHiddenFiles, defaultLineEnding, AgentSettings.defaults());
    }

    public static EditorSettings defaults() {
        return new EditorSettings("Hack", 14, "Hack", 15, 4,
                true, false, true, true, true, false, "LF");
    }

    public EditorSettings withFonts(String uiFamily, int uiSize, String editorFamily, int editorSize) {
        return new EditorSettings(uiFamily, uiSize, editorFamily, editorSize, tabSize,
                useSpaces, softWrap, showLineNumbers, highlightCurrentLine, showMinimap, showHiddenFiles, defaultLineEnding, agents, syntaxHighlighting);
    }

    public EditorSettings withAgents(AgentSettings value) {
        return new EditorSettings(uiFontFamily, uiFontSize, editorFontFamily, editorFontSize, tabSize,
                useSpaces, softWrap, showLineNumbers, highlightCurrentLine, showMinimap, showHiddenFiles, defaultLineEnding, value, syntaxHighlighting);
    }

    public EditorSettings withSoftWrap(boolean value) {
        return new EditorSettings(uiFontFamily, uiFontSize, editorFontFamily, editorFontSize, tabSize,
                useSpaces, value, showLineNumbers, highlightCurrentLine, showMinimap, showHiddenFiles, defaultLineEnding, agents, syntaxHighlighting);
    }

    public EditorSettings withSyntaxHighlighting(boolean value) {
        return new EditorSettings(uiFontFamily, uiFontSize, editorFontFamily, editorFontSize, tabSize,
                useSpaces, softWrap, showLineNumbers, highlightCurrentLine, showMinimap, showHiddenFiles, defaultLineEnding, agents, value);
    }

    private static String requireFamily(String family) {
        Objects.requireNonNull(family, "Font family");
        String trimmed = family.strip();
        if (trimmed.isEmpty() || trimmed.length() > 160 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Choose a valid font family");
        }
        return trimmed;
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
