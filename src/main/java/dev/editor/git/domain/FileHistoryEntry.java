package dev.editor.git.domain;

/** Paths and selected ranges belong to this historical revision, including across renames. */
public record FileHistoryEntry(GitCommitHeader commit, GitChange change,
                               FileHistoryQuery.Lines before, FileHistoryQuery.Lines after) { }
