package dev.editor.diff.domain;

public enum Side {
    LEFT, RIGHT;
    public Side opposite() { return this == LEFT ? RIGHT : LEFT; }
}
