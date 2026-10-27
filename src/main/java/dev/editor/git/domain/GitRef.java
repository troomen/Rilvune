package dev.editor.git.domain;
public record GitRef(String name, String objectId) {
    public String label() { return name.replaceFirst("^refs/(heads|remotes|tags)/", ""); }
    public String group() { return name.startsWith("refs/heads/") ? "Local branches" : name.startsWith("refs/remotes/") ? "Remote branches · local state" : "Tags"; }
}
