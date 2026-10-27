package dev.editor.git.domain;
/** Reference is resolved before paging so an external branch movement cannot shift page boundaries. */
public record HistoryQuery(java.util.List<String> tips, String message, String author, String path) {
    public HistoryQuery { tips = java.util.List.copyOf(tips); for (String id : tips) GitSource.tree(id, id); message = message == null ? "" : message; author = author == null ? "" : author; path = path == null ? "" : path; }
    public boolean filtered() { return !message.isBlank() || !author.isBlank() || !path.isBlank(); }
}
