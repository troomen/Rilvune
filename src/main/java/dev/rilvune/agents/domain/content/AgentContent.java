package dev.rilvune.agents.domain.content;

/** Protocol-independent content. References are displayed without fetching their targets. */
public sealed interface AgentContent {
    String text();
    default long retainedCharacters() { return text().length(); }
    record Text(String text) implements AgentContent { }
    record Diff(String path, String oldText, String newText) implements AgentContent {
        public String text() { return "File: " + path + "\nBefore\n" + oldText + "\nAfter\n" + newText; }
    }
    record Resource(String name, String uri, String mimeType, String text) implements AgentContent { public long retainedCharacters() { return (long)name.length() + uri.length() + mimeType.length() + text.length(); } }
    record Media(String kind, String mimeType, String data, String uri) implements AgentContent {
        public long retainedCharacters() { return (long)data.length() + text().length(); }
        public String text() { return kind + " · " + mimeType + (uri.isBlank() ? "" : "\n" + uri); }
    }
    record Terminal(String id) implements AgentContent { public String text() { return "Terminal · " + id; } }
    record Unknown(String kind, String text) implements AgentContent { }
}
