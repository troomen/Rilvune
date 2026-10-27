package dev.editor.platform.text.ui;

/** Presentation and typing preferences, independent of file persistence. */
public record TextEditorSettings(int tabSize, boolean useSpaces, boolean softWrap,
                                 boolean showLineNumbers, boolean highlightCurrentLine, boolean showMinimap, boolean syntaxHighlighting) {
    public TextEditorSettings {
        if (tabSize < 1) throw new IllegalArgumentException("Tab size must be positive");
    }
    public TextEditorSettings(int tabSize, boolean useSpaces, boolean softWrap, boolean showLineNumbers, boolean highlightCurrentLine, boolean showMinimap) {
        this(tabSize, useSpaces, softWrap, showLineNumbers, highlightCurrentLine, showMinimap, true);
    }
    public TextEditorSettings withSoftWrap(boolean value) {
        return new TextEditorSettings(tabSize, useSpaces, value, showLineNumbers, highlightCurrentLine, showMinimap, syntaxHighlighting);
    }
    public TextEditorSettings withSyntaxHighlighting(boolean value) {
        return new TextEditorSettings(tabSize, useSpaces, softWrap, showLineNumbers, highlightCurrentLine, showMinimap, value);
    }
}
