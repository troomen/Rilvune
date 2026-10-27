package dev.editor.git.infrastructure;

import dev.editor.git.domain.*;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** NUL framing is independent of filenames, subjects, punctuation and user output preferences. */
final class GitProtocol {
    private GitProtocol() { }
    static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new IOException("Unsupported encoding: expected valid UTF-8", failure); }
    }
    static List<String> fields(byte[] bytes) throws IOException { return new ArrayList<>(Arrays.asList(utf8(bytes).split("\0", -1))); }
    static List<GitChange> status(byte[] bytes) throws IOException {
        List<String> fields = fields(bytes); List<GitChange> changes = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            String record = fields.get(i); if (record.isEmpty()) continue;
            char type = record.charAt(0);
            if (type == '?') { changes.add(new GitChange(record.substring(2), record.substring(2), "Untracked", '?', '?', false)); continue; }
            if (type == '!') continue;
            int columns = type == '1' ? 9 : type == '2' ? 10 : type == 'u' ? 11 : 0;
            if (columns == 0) throw new IOException("Unsupported status record");
            String[] parts = record.split(" ", columns);
            if (parts.length != columns || parts[1].length() != 2) throw new IOException("Malformed status record");
            String path = parts[columns - 1]; String old = type == '2' ? fields.get(++i) : path;
            char index = parts[1].charAt(0), worktree = parts[1].charAt(1);
            String label = type == 'u' ? "Unmerged" : type == '2' ? "Renamed" : label(index != '.' ? index : worktree);
            if (!parts[2].equals("N...")) label += " · submodule " + parts[2];
            changes.add(new GitChange(path, old, label, index, worktree, type == 'u'));
        }
        return List.copyOf(changes);
    }
    static List<GitChange> changes(byte[] bytes) throws IOException {
        List<String> fields = fields(bytes); List<GitChange> changes = new ArrayList<>();
        for (int i = 0; i < fields.size() && !fields.get(i).isEmpty();) {
            String code = fields.get(i++); String old = fields.get(i++); char kind = code.charAt(0);
            String path = kind == 'R' || kind == 'C' ? fields.get(i++) : old;
            changes.add(new GitChange(path, old, label(kind), '.', '.', false));
        }
        return List.copyOf(changes);
    }
    static List<GitCommit> commits(byte[] bytes) throws IOException {
        List<String> fields = fields(bytes); List<GitCommit> commits = new ArrayList<>();
        for (int i = 0; i + 4 < fields.size(); i += 5) {
            String id = fields.get(i); GitSource.tree(id, id);
            commits.add(new GitCommit(id, fields.get(i + 1).isBlank() ? List.of() : List.of(fields.get(i + 1).split(" ")),
                    fields.get(i + 2), fields.get(i + 3), fields.get(i + 4).stripTrailing()));
        }
        return List.copyOf(commits);
    }
    private static String label(char code) { return switch (code) { case 'A' -> "Added"; case 'D' -> "Deleted"; case 'R' -> "Renamed"; case 'C' -> "Copied"; case 'T' -> "Type changed"; case 'U' -> "Unmerged"; default -> "Modified"; }; }
}
