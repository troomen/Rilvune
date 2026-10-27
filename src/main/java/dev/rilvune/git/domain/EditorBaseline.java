package dev.rilvune.git.domain;

import dev.rilvune.platform.text.domain.NormalizedText;

/** Pinned source for live-editor comparisons. Empty new files have a positively known absent tree entry. */
public record EditorBaseline(RepositoryLocation repository, String commitId, GitFileRevision file, NormalizedText source) { }
