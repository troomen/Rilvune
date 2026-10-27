package dev.rilvune.git.domain;
import java.util.List;
public record RepositoryState(String head, String branch, List<GitRef> refs, List<GitChange> changes, String fingerprint) {
    public RepositoryState { refs = List.copyOf(refs); changes = List.copyOf(changes); }
}
