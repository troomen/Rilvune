package dev.rilvune.git.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Mutating Git is confined to isolated fixture setup; production only has the query port. */
public final class GitFixture {
    public final Path root;
    public GitFixture(Path root) throws Exception { this(root, false); }
    public GitFixture(Path root, boolean sha256) throws Exception { this.root = root; Files.createDirectories(root); git("init", "-b", "main", sha256 ? "--object-format=sha256" : "--object-format=sha1"); git("config", "user.name", "Example Author"); git("config", "user.email", "author@example.test"); git("config", "commit.gpgsign", "false"); }
    public String git(String... args) throws Exception { List<String> command = new ArrayList<>(List.of("git", "-c", "protocol.file.allow=always")); command.addAll(List.of(args)); Process p = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start(); byte[] result = p.getInputStream().readAllBytes(); if (p.waitFor() != 0) throw new IOException(new String(result, StandardCharsets.UTF_8)); return new String(result, StandardCharsets.UTF_8).strip(); }
    public void write(String path, String content) throws IOException { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, content); }
    public String commit(String message) throws Exception { git("add", "--all"); git("commit", "-m", message); return git("rev-parse", "HEAD"); }
    public Map<String, String> snapshot() throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(root)) { for (Path file : files.toList()) { String key = root.relativize(file).toString(); if (Files.isSymbolicLink(file)) result.put(key, "link:" + Files.readSymbolicLink(file)); else if (Files.isDirectory(file)) result.put(key, "directory:" + Files.getPosixFilePermissions(file)); else result.put(key, Base64.getEncoder().encodeToString(Files.readAllBytes(file)) + ":" + Files.getPosixFilePermissions(file)); } }
        return result;
    }
}
