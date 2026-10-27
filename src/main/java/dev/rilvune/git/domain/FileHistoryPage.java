package dev.rilvune.git.domain;

import java.util.List;

public record FileHistoryPage(List<FileHistoryEntry> entries, boolean hasMore) {
    public FileHistoryPage { entries = List.copyOf(entries); }
}
