package dev.rilvune.editor.domain;

/** Identity and observable state of one page, distinct from its underlying resources. */
public interface EditorTabModel<S extends EditorTabState> {
    String id();
    S state();
}
