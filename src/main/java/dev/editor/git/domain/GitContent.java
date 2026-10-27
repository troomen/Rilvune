package dev.editor.git.domain;
/** Unavailable/binary content is explicit; empty text alone never means an error. */
public record GitContent(String text, String metadata, String explanation, boolean comparable, boolean byteOrderMark) {
    public GitContent(String text, String metadata, String explanation, boolean comparable) {
        this(text, metadata, explanation, comparable, false);
    }
    public static GitContent empty() { return new GitContent("", "Absent", "", true); }
    public static GitContent unavailable(String message) { return new GitContent("", "Unavailable", message, false); }
}
