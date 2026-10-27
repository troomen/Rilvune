package dev.editor.git.domain;
import java.util.List;
public record HistoryPage(List<GitCommitHeader> commits, boolean hasMore) { public HistoryPage { commits = List.copyOf(commits); } }
