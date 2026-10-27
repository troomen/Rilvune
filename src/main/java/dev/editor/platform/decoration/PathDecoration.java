package dev.editor.platform.decoration;

/** Presentation semantics shared by filesystem views; independent of a VCS implementation. */
public record PathDecoration(Tone tone, String description) {
    public enum Tone { MODIFIED, UNTRACKED, ADDED, CONFLICT, IGNORED }
}
