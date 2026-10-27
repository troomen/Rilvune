package dev.rilvune.git.domain;
/** Paths are raw repository-relative names, never display-escaped or shell-quoted. */
public record GitChange(String path, String oldPath, String status, char index, char worktree, boolean unmerged) {
    public String displayPath() { return oldPath.equals(path) ? escape(path) : escape(oldPath) + " → " + escape(path); }
    public String identity() { return oldPath + "\0" + path; }
    public boolean untracked() { return index == '?'; }
    public static String escape(String path) { return path.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"); }
}
