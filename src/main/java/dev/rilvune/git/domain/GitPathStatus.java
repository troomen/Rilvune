package dev.rilvune.git.domain;

public record GitPathStatus(Kind kind, String description) {
    public enum Kind { MODIFIED, UNTRACKED, ADDED, CONFLICT, IGNORED }
    public static GitPathStatus from(GitChange change) {
        Kind kind = change.unmerged() ? Kind.CONFLICT : change.untracked() ? Kind.UNTRACKED
                : change.index() == 'A' ? Kind.ADDED : Kind.MODIFIED;
        return new GitPathStatus(kind, "Git: " + change.status() + " · index " + change.index()
                + " / working tree " + change.worktree() + (change.path().equals(change.oldPath()) ? "" : " · from " + GitChange.escape(change.oldPath())));
    }
}
