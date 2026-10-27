package dev.rilvune.git.domain;
import java.util.List;
public record GitCommit(String id, List<String> parents, String author, String date, String message) {
    public GitCommit { parents = List.copyOf(parents); }
    public GitCommitHeader header() { return new GitCommitHeader(id, parents, author, date, subject()); }
    public String subject() { return message.lines().findFirst().orElse(""); }
    public String shortId() { return id.substring(0, Math.min(10, id.length())); }
}
