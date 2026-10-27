package dev.editor.git.domain;
public record GitSource(Kind kind, String objectId, int stage, String label) {
    public enum Kind { EMPTY, TREE, INDEX, WORKTREE }
    public GitSource {
        if (kind == Kind.TREE && !objectId.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalArgumentException("A full object ID is required");
        if (stage < 0 || stage > 3) throw new IllegalArgumentException("Invalid index stage");
    }
    public static GitSource empty() { return new GitSource(Kind.EMPTY, "", 0, "Empty tree"); }
    public static GitSource tree(String id, String label) { return new GitSource(Kind.TREE, id, 0, label); }
    public static GitSource index() { return index(0); }
    public static GitSource index(int stage) { return new GitSource(Kind.INDEX, "", stage, stage == 0 ? "Index" : switch(stage) { case 1 -> "Base · stage 1"; case 2 -> "Ours · stage 2"; default -> "Theirs · stage 3"; }); }
    public static GitSource working() { return new GitSource(Kind.WORKTREE, "", 0, "Working tree · disk snapshot"); }
    public boolean volatileContent() { return kind == Kind.INDEX || kind == Kind.WORKTREE; }
}
