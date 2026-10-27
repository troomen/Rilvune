package dev.editor.git.infrastructure;

import dev.editor.git.domain.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Native Git metadata and raw objects; never invokes textconv, filters, hooks or a shell. */
public final class CliGitRepository implements GitRepository {
    private static final String COMMIT_FORMAT = "--format=%H%x00%P%x00%an%x00%aI%x00%B";
    private final GitConfiguration configuration;
    private final GitProcess process;
    private final Map<String, GitContent> blobs = new LinkedHashMap<>(16, .75f, true);
    private int cachedBytes;
    private int cachedCommitBytes;
    private final Map<String, GitCommit> commitCache = new LinkedHashMap<>(16, .75f, true);
    public CliGitRepository(GitConfiguration configuration) { this.configuration = configuration; process = new GitProcess(configuration); }
    @Override public RepositoryLocation locate(Path project) throws IOException {
        Path root = project.toRealPath();
        if (!Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("This project root has no .git repository. Parent and nested repositories are not searched.");
        Path top = Path.of(text(root, "rev-parse", "--path-format=absolute", "--show-toplevel").strip()).toRealPath();
        if (!top.equals(root)) throw new IOException("The project directory must itself be the working-tree root");
        Path directory = Path.of(text(root, "rev-parse", "--absolute-git-dir").strip());
        Path common = Path.of(text(root, "rev-parse", "--path-format=absolute", "--git-common-dir").strip());
        if (!Files.isDirectory(directory) || !Files.isDirectory(common)) throw new IOException("Git metadata is unavailable. Mount the worktree's Git/common directories at their original absolute paths.");
        return new RepositoryLocation(root, directory, common, directoryIdentity(directory) + directoryIdentity(common));
    }
    @Override public GitFileRevision fileRevision(RepositoryLocation repository, String commit, String path) throws IOException {
        validateId(commit); path = checkedPath(path);
        localMetadata(repository);
        List<String> rows = GitProtocol.fields(query(repository.root(), "ls-tree", "-z", commit, "--", path));
        String row = rows.stream().filter(value -> !value.isEmpty()).findFirst().orElse("");
        if (row.isEmpty()) return new GitFileRevision("", path, GitContent.empty());
        int separator = row.indexOf('\t');
        if (separator < 0 || !row.substring(separator + 1).equals(path)) throw new IOException("Unexpected tree entry");
        String[] fields = row.substring(0, separator).split(" ");
        if (fields.length != 3) throw new IOException("Malformed tree entry");
        if (!fields[0].equals("100644") && !fields[0].equals("100755"))
            return new GitFileRevision(fields[2], path, GitContent.unavailable("Only regular committed text files support editor inspection"));
        return new GitFileRevision(fields[2], path, blob(repository, fields[2], fields[0]));
    }
    @Override public List<GitBlameLine> blame(RepositoryLocation repository, String commit, String path) throws IOException {
        validateId(commit); path = checkedPath(path); localMetadata(repository);
        return GitBlameParser.parse(query(repository.root(), "blame", "--line-porcelain", "--root", "--encoding=UTF-8", "--no-textconv", "--ignore-revs-file", "", commit, "--", path));
    }
    @Override public RepositoryState state(RepositoryLocation repository) throws IOException {
        LocalGitStatus local = status(repository);
        return new RepositoryState(local.head(), local.branch(), references(repository), local.changes(), local.fingerprint());
    }
    @Override public List<GitRef> references(RepositoryLocation repository) throws IOException {
        List<GitRef> refs = new ArrayList<>();
        String raw = text(repository.root(), "for-each-ref", "--format=%(refname)%00%(objectname)%00%(*objectname)%00%(objecttype)%00%(*objecttype)", "refs/heads", "refs/remotes", "refs/tags");
        for (String row : raw.lines().toList()) {
            String[] values = row.split("\0", -1);
            if (values.length != 5) throw new IOException("Malformed reference record");
            String id = values[2].isEmpty() ? values[1] : values[2];
            if (values[3].equals("commit") || values[4].equals("commit")) refs.add(new GitRef(values[0], id));
        }
        return List.copyOf(refs);
    }
    @Override public LocalGitStatus status(RepositoryLocation repository) throws IOException {
        Path root = repository.root();
        for (int attempt = 0; attempt < 3; attempt++) {
            String before = localMetadata(repository);
            String head = optionalId(root, "HEAD");
            GitProcess.Result branch = process.run(root, "symbolic-ref", "--quiet", "--short", "HEAD");
            if (branch.exit() > 1) checked(branch);
            List<GitChange> changes = GitProtocol.status(query(root, "status", "--porcelain=v2", "-z", "--untracked-files=all", "--ignore-submodules=none", "--renames"));
            Map<String, GitChange> union = new LinkedHashMap<>();
            for (GitChange change : changes) union.merge(change.path(), change, (a, b) -> new GitChange(a.path(), a.oldPath(), a.status() + " (index) · " + b.status() + " (working tree)", a.index(), b.worktree(), a.unmerged() || b.unmerged()));
            String references = referenceFingerprint(repository);
            String after = localMetadata(repository);
            if (!before.equals(after)) continue;
            List<GitChange> result = List.copyOf(union.values());
            return new LocalGitStatus(head, branch.exit() == 0 ? GitProtocol.utf8(branch.output()).strip() : "Detached HEAD", result, statusFingerprint(repository, result), references);
        }
        throw new IOException("Repository metadata changed while loading; refreshing again");
    }
    @Override public String statusFingerprint(RepositoryLocation repository, List<GitChange> paths) throws IOException {
        StringBuilder fingerprint = new StringBuilder(localMetadata(repository));
        for (GitChange change : paths) {
            Path path = RepositoryPaths.resolve(repository.root(), change.path());
            fingerprint.append(change);
            try {
                if (!path.getParent().toRealPath().startsWith(repository.root())) { fingerprint.append("outside-symlink"); continue; }
            } catch (NoSuchFileException absent) { fingerprint.append("absent-parent"); continue; }
            fingerprint.append(attributes(path));
        }
        return fingerprint.toString();
    }
    private String directoryIdentity(Path directory) throws IOException {
        BasicFileAttributes value = Files.readAttributes(directory, BasicFileAttributes.class);
        return directory + ":" + value.fileKey() + ":" + value.creationTime();
    }
    private String localMetadata(RepositoryLocation repository) throws IOException {
        if (!repository.incarnation().isEmpty() && !repository.incarnation().equals(directoryIdentity(repository.gitDirectory()) + directoryIdentity(repository.commonDirectory())))
            throw new IOException("Repository replaced while loading; refreshing again");
        return attributes(repository.gitDirectory()) + attributes(repository.gitDirectory().resolve("HEAD"))
                + attributes(repository.gitDirectory().resolve("index")) + optionalId(repository.root(), "HEAD");
    }
    private String referenceFingerprint(RepositoryLocation repository) throws IOException {
        StringBuilder fingerprint = new StringBuilder(attributes(repository.commonDirectory().resolve("packed-refs")));
        Path refs = repository.commonDirectory().resolve("refs");
        if (Files.exists(refs, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.walk(refs)) {
                int count = 0;
                for (Path path : entries.limit(20001).sorted().toList()) {
                    if (++count > 20000) throw new IOException("Reference metadata exceeds the 20,000 entry limit");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Status request cancelled");
                    fingerprint.append(attributes(path));
                }
            }
        }
        return fingerprint.toString();
    }
    @Override public Set<String> ignored(RepositoryLocation repository, List<String> paths) throws IOException {
        if (paths.size() > 512) throw new IOException("Ignored-path batch exceeds 512 paths");
        ByteArrayOutputStream input = new ByteArrayOutputStream(); Set<String> allowed = new HashSet<>();
        for (String path : paths) {
            Path resolved = RepositoryPaths.resolve(repository.root(), path);
            // Git check-ignore must not traverse a directory symlink outside this repository.
            try { if (!resolved.getParent().toRealPath().startsWith(repository.root())) continue; }
            catch (NoSuchFileException missing) { continue; }
            input.writeBytes(("./" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8)); input.write(0); allowed.add("./" + path);
            if (input.size() > 256 * 1024) throw new IOException("Ignored-path input exceeds 256 KiB");
        }
        if (allowed.isEmpty()) return Set.of();
        GitProcess.Result result = process.run(repository.root(), List.of("check-ignore", "--stdin", "-z"), input.toByteArray());
        if (result.exit() != 0 && result.exit() != 1) checked(result);
        Set<String> ignored = new HashSet<>();
        for (String path : GitProtocol.fields(result.output())) if (!path.isEmpty()) {
            if (!allowed.contains(path)) throw new IOException("Unexpected ignored-path response");
            ignored.add(path.substring(2));
        }
        return Set.copyOf(ignored);
    }
    @Override public String ignoreFingerprint(RepositoryLocation repository, Set<String> paths) throws IOException {
        Set<Path> rules = new TreeSet<>();
        for (String relative : paths) {
            Path path = RepositoryPaths.resolve(repository.root(), relative).getParent();
            if (!Files.exists(path) || !path.toRealPath().startsWith(repository.root())) continue;
            while (path != null && path.startsWith(repository.root())) { rules.add(path.resolve(".gitignore")); path = path.getParent(); }
            if (rules.size() > 20000) throw new IOException("Ignore-rule metadata exceeds the configured limit");
        }
        rules.add(repository.commonDirectory().resolve("info/exclude")); rules.add(repository.commonDirectory().resolve("config"));
        rules.add(repository.gitDirectory().resolve("config.worktree")); rules.add(repository.gitDirectory().resolve("index"));
        GitProcess.Result excludes = process.run(repository.root(), "config", "--path", "--get", "core.excludesFile");
        if (excludes.exit() != 0 && excludes.exit() != 1) checked(excludes);
        if (excludes.exit() == 0) {
            String configured = GitProtocol.utf8(excludes.output());
            if (configured.endsWith("\n")) configured = configured.substring(0, configured.length() - 1);
            if (!configured.isEmpty()) rules.add(repository.root().resolve(Path.of(configured)).normalize());
        }
        StringBuilder fingerprint = new StringBuilder();
        for (Path rule : rules) fingerprint.append(attributes(rule));
        return fingerprint.toString();
    }
    @Override public HistoryPage history(RepositoryLocation repository, HistoryQuery query, int offset, int count) throws IOException {
        if (offset < 0 || count < 1 || count > 500) throw new IOException("Invalid history page");
        if (query.tips().isEmpty()) return new HistoryPage(List.of(), false);
        List<String> args = new ArrayList<>(List.of("log", "--no-show-signature", "--no-notes", "--no-patch", "--topo-order", "-z", COMMIT_FORMAT.replace("%B", "%s"),
                "--skip=" + offset, "--max-count=" + (count + 1), "--fixed-strings", "--regexp-ignore-case"));
        if (!query.message().isBlank()) args.add("--grep=" + query.message());
        if (!query.author().isBlank()) args.add("--author=" + query.author());
        args.addAll(query.tips()); args.add("--"); if (!query.path().isBlank()) args.add(checkedPath(query.path()));
        List<GitCommitHeader> commits = GitProtocol.commits(query(repository.root(), args)).stream().map(GitCommit::header).toList();
        return new HistoryPage(commits.subList(0, Math.min(count, commits.size())), commits.size() > count);
    }
    @Override public FileHistoryPage fileHistory(RepositoryLocation repository, FileHistoryQuery query, int offset, int count) throws IOException {
        if (offset < 0 || offset > 20000 || count < 1 || count > 500) throw new IOException("Invalid file history page");
        validateId(query.startCommit()); String path = checkedPath(query.path());
        boolean lines = query.selection() != null;
        String format = "%H%x00%P%x00%an%x00%aI%x00%s%x00";
        List<String> args = new ArrayList<>(List.of("log", "--root", "--no-show-signature", "--no-notes", "--no-decorate", "--encoding=UTF-8", "--no-ext-diff", "--no-textconv",
                "--max-count=" + (offset + count + 1), "--format=" + (lines ? "%x00" : "") + format));
        if (lines) {
            args.addAll(List.of("--src-prefix=a/", "--dst-prefix=b/",
                    "-L" + (query.selection().first() + 1) + "," + query.selection().end() + ":" + path, query.startCommit()));
        } else args.addAll(List.of("--follow", "--diff-merges=first-parent", "--name-status", "-z", query.startCommit(), "--", path));
        byte[] output = query(repository.root(), args);
        if (lines) return FileHistoryParser.selection(output, commit -> changes(repository,
                commit.parents().isEmpty() ? GitSource.empty() : GitSource.tree(commit.parents().getFirst(), "Parent"),
                GitSource.tree(commit.id(), commit.shortId())), offset, count);
        List<FileHistoryEntry> entries = FileHistoryParser.files(output);
        int start = Math.min(offset, entries.size()), end = Math.min(offset + count, entries.size());
        return new FileHistoryPage(entries.subList(start, end), entries.size() > end);
    }
    @Override public GitCommit commit(RepositoryLocation repository, String id) throws IOException {
        validateId(id); String key = repository.commonDirectory() + ":" + id;
        synchronized (commitCache) { GitCommit cached = commitCache.get(key); if (cached != null) return cached; }
        List<GitCommit> rows = GitProtocol.commits(query(repository.root(), "log", "--no-show-signature", "--no-notes", "--no-patch", "-z", "-1", COMMIT_FORMAT, id, "--"));
        if (rows.isEmpty()) throw new IOException("Commit is unavailable");
        synchronized (commitCache) {
            int cost = commitCost(rows.getFirst()), budget = configuration.cacheBytes() / 4;
            if (cost <= budget && !commitCache.containsKey(key)) {
                while (!commitCache.isEmpty() && (commitCache.size() >= 200 || cachedCommitBytes + cost > budget)) cachedCommitBytes -= commitCost(commitCache.remove(commitCache.keySet().iterator().next()));
                commitCache.put(key, rows.getFirst()); cachedCommitBytes += cost;
            }
        }
        return rows.getFirst();
    }
    @Override public String resolve(RepositoryLocation repository, String revision) throws IOException {
        if (revision.isBlank() || revision.startsWith("-") || revision.indexOf('\0') >= 0 || revision.length() > 1024) throw new IOException("Invalid revision");
        String result = optionalId(repository.root(), revision);
        if (result.isEmpty()) throw new IOException("Revision is unavailable: " + revision); return result;
    }
    @Override public List<GitChange> changes(RepositoryLocation repository, GitSource left, GitSource right) throws IOException {
        if (left.kind() == GitSource.Kind.EMPTY && right.kind() == GitSource.Kind.TREE) {
            return GitProtocol.changes(query(repository.root(), "diff-tree", "--root", "--no-commit-id", "-r", "--name-status", "-z", "--no-ext-diff", "--no-textconv", "-M", right.objectId(), "--"));
        }
        List<String> args = new ArrayList<>(List.of("diff", "--name-status", "-z", "--no-ext-diff", "--no-textconv", "--ignore-submodules=none", "-M"));
        if (right.kind() == GitSource.Kind.INDEX) args.add("--cached");
        if (left.kind() == GitSource.Kind.TREE) args.add(left.objectId());
        if (right.kind() == GitSource.Kind.TREE) args.add(right.objectId());
        args.add("--"); return GitProtocol.changes(query(repository.root(), args));
    }
    @Override public GitContent content(RepositoryLocation repository, GitSource source, String rawPath) throws IOException {
        if (source.kind() == GitSource.Kind.EMPTY) return GitContent.empty();
        String path = checkedPath(rawPath);
        if (source.kind() == GitSource.Kind.WORKTREE) return workingContent(repository.root(), path);
        String row;
        if (source.kind() == GitSource.Kind.TREE) {
            List<String> rows = GitProtocol.fields(query(repository.root(), "ls-tree", "-z", source.objectId(), "--", path));
            row = rows.stream().filter(value -> !value.isEmpty()).findFirst().orElse("");
            if (row.isEmpty()) return GitContent.empty();
            String[] parts = row.substring(0, row.indexOf('\t')).split(" ");
            if (parts[1].equals("commit")) return new GitContent(parts[2] + "\n", "Submodule pointer · mode " + parts[0], "Recorded submodule ID; no recursion or checkout", true);
            return blob(repository, parts[2], parts[0]);
        }
        List<String> rows = GitProtocol.fields(query(repository.root(), "ls-files", "--stage", "-z", "--", path));
        for (String value : rows) {
            if (value.isEmpty()) continue;
            String[] parts = value.substring(0, value.indexOf('\t')).split(" ");
            if (Integer.parseInt(parts[2]) == source.stage()) {
                if (parts[0].equals("160000")) return new GitContent(parts[1] + "\n", "Submodule pointer", "Recorded submodule ID", true);
                return blob(repository, parts[1], parts[0]);
            }
        }
        if (source.stage() > 0) return GitContent.unavailable("This conflict has no " + source.label() + " entry");
        return GitContent.empty();
    }
    private int commitCost(GitCommit commit) { return (commit.message().length() + commit.author().length() + commit.id().length() + commit.date().length() + commit.parents().size() * 64) * 2 + 512; }
    private GitContent workingContent(Path root, String path) throws IOException {
        Path file = root.resolve(path).normalize();
        try { Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } catch (NoSuchFileException missing) { return GitContent.empty(); }
        if (!file.getParent().toRealPath().startsWith(root)) return GitContent.unavailable("Path traverses a symlink outside this repository");
        if (Files.isSymbolicLink(file)) return new GitContent(Files.readSymbolicLink(file).toString(), "Symlink target", "Symlink target text; link is not followed", true);
        if (Files.isDirectory(file)) return GitContent.unavailable("Submodule/directory working state; inspect its recorded pointer in Index or History");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return GitContent.unavailable("Not a regular file");
        if (Files.size(file) > configuration.textLimit()) return GitContent.unavailable("Content exceeds the 20 MiB text limit");
        // Bounded stream, even when a file grows between attributes and read.
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(configuration.textLimit() + 1);
            if (bytes.length > configuration.textLimit()) return GitContent.unavailable("Content exceeds the configured text limit");
            return decode(bytes, Files.isExecutable(file) ? "100755" : "100644");
        }
    }
    private GitContent blob(RepositoryLocation repository, String id, String mode) throws IOException {
        validateId(id); String key = repository.commonDirectory() + ":" + id + ":" + mode;
        synchronized (blobs) { GitContent cached = blobs.get(key); if (cached != null) return cached; }
        long size = Long.parseLong(text(repository.root(), "cat-file", "-s", id).strip());
        if (size > configuration.textLimit()) return GitContent.unavailable("Blob " + id + " · " + size + " bytes exceeds the text limit");
        GitContent content = decode(query(repository.root(), "cat-file", "blob", id), mode);
        int cost = content.text().length() * 2 + 512;
        synchronized (blobs) {
            if (cost <= (configuration.cacheBytes() - configuration.cacheBytes() / 4)) {
                while (!blobs.isEmpty() && cachedBytes + cost > (configuration.cacheBytes() - configuration.cacheBytes() / 4)) { GitContent removed = blobs.remove(blobs.keySet().iterator().next()); cachedBytes -= removed.text().length() * 2 + 512; }
                if (!blobs.containsKey(key)) { blobs.put(key, content); cachedBytes += cost; }
            }
        }
        return content;
    }
    private GitContent decode(byte[] bytes, String mode) {
        for (byte value : bytes) if (value == 0) return new GitContent("", "Binary · " + bytes.length + " bytes · mode " + mode, "Binary content is not rendered as text", false);
        try {
            String text = GitProtocol.utf8(bytes);
            boolean bom = text.startsWith("\ufeff"); if (bom) text = text.substring(1);
            String eol = text.contains("\r\n") ? "CRLF" : text.contains("\r") ? "CR" : "LF";
            String info = "UTF-8" + (bom ? " BOM" : "") + " · " + eol + " · " + (text.endsWith("\n") ? "final newline" : "no final newline") + " · mode " + mode;
            String explanation = mode.equals("120000") ? "Recorded symlink target; link is not followed" : text.startsWith("version https://git-lfs.github.com/spec/v1\n") ? "Git LFS pointer · objects are never downloaded" : "";
            return new GitContent(text, info, explanation, true, bom);
        } catch (IOException failure) { return GitContent.unavailable(failure.getMessage() + " · " + bytes.length + " bytes"); }
    }
    @Override public String fingerprint(RepositoryLocation repository, List<GitChange> changes) throws IOException {
        StringBuilder fingerprint = new StringBuilder();
        for (Path file : List.of(repository.gitDirectory().resolve("HEAD"), repository.gitDirectory().resolve("index"), repository.commonDirectory().resolve("packed-refs"))) fingerprint.append(attributes(file));
        fingerprint.append(optionalId(repository.root(), "HEAD"));
        fingerprint.append(text(repository.root(), "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads", "refs/remotes", "refs/tags"));
        for (GitChange change : changes) { fingerprint.append(change.identity()).append(change.index()).append(change.worktree()); fingerprint.append(attributes(repository.root().resolve(checkedPath(change.path())))); }
        return fingerprint.toString();
    }
    private String attributes(Path file) throws IOException {
        try { BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return file + ":" + attributes.lastModifiedTime() + ":" + attributes.size() + ":" + attributes.fileKey() + ";"; }
        catch (NoSuchFileException missing) { return file + ":absent;"; }
    }
    private String optionalId(Path root, String revision) throws IOException {
        GitProcess.Result result = process.run(root, "rev-parse", "--verify", "--quiet", "--end-of-options", revision + "^{commit}");
        if (result.exit() == 1) return "";
        String id = GitProtocol.utf8(checked(result)).strip(); validateId(id); return id;
    }
    private static void validateId(String id) throws IOException { if (!id.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid full Git object ID"); }
    private String checkedPath(String path) throws IOException { if (path.isEmpty() || path.indexOf('\0') >= 0 || Path.of(path).isAbsolute() || !Path.of(path).normalize().equals(Path.of(path)) || path.equals("..") || path.startsWith("../")) throw new IOException("Invalid repository path"); return path; }
    private String text(Path root, String... arguments) throws IOException { return GitProtocol.utf8(query(root, arguments)); }
    private byte[] query(Path root, String... arguments) throws IOException { return checked(process.run(root, arguments)); }
    private byte[] query(Path root, List<String> arguments) throws IOException { return checked(process.run(root, arguments)); }
    private byte[] checked(GitProcess.Result result) throws IOException { if (result.exit() != 0) throw new IOException("Git query unavailable: " + result.error()); return result.output(); }
}
