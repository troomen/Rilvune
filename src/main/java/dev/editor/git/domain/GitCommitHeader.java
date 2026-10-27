package dev.editor.git.domain;
import java.util.List;
/** History rows omit full messages; details are loaded on selection and cached separately. */
public record GitCommitHeader(String id, List<String> parents, String author, String date, String subject) {
    public GitCommitHeader { parents = List.copyOf(parents); }
    public String shortId() { return id.substring(0, Math.min(10, id.length())); }
}
