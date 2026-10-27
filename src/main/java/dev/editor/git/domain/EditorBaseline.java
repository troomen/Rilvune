package dev.editor.git.domain;

import dev.editor.platform.text.domain.NormalizedText;

/** Pinned source for live-editor comparisons. Empty new files have a positively known absent tree entry. */
public record EditorBaseline(RepositoryLocation repository, String commitId, GitFileRevision file, NormalizedText source) { }
