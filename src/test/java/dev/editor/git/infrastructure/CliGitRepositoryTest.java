package dev.editor.git.infrastructure;

import dev.editor.git.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliGitRepositoryTest {
    @TempDir Path temporary;
    private CliGitRepository backend;
    @BeforeEach void setup() { backend = new CliGitRepository(GitConfiguration.defaults()); }
    private HistoryQuery all(RepositoryState state) { Set<String> tips = new LinkedHashSet<>(); state.refs().forEach(ref -> tips.add(ref.objectId())); if (!state.head().isEmpty()) tips.add(state.head()); return new HistoryQuery(List.copyOf(tips), "", "", ""); }
    @Test void rootDetectionAndUnbornRepository() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); RepositoryLocation repo = backend.locate(fixture.root);
        assertEquals(fixture.root.resolve(".git"), repo.gitDirectory());
        fixture.write("untracked.txt", "hello\n"); RepositoryState state = backend.state(repo); assertEquals("", state.head()); assertEquals("main", state.branch()); assertTrue(state.changes().getFirst().untracked());
        assertEquals(List.of(), backend.history(repo, all(state), 0, 200).commits());
        Files.createDirectories(fixture.root.resolve("child")); assertThrows(java.io.IOException.class, () -> backend.locate(fixture.root.resolve("child")));
        fixture.git("add", "."); assertEquals(1, backend.changes(repo, GitSource.empty(), GitSource.index()).size());
    }
    @Test void queryOnlyForRealHistoryIndexAndWorktreeWithUnusualNames() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); String name = "-odd name\twith\nЮникод.txt";
        fixture.write(name, "one\n"); String a = fixture.commit("Root\n\nMultiline · message | punctuation");
        fixture.write(name, "two\n"); fixture.git("add", "--", name); fixture.write(name, "three\n"); fixture.write("untracked.txt", "new\n"); fixture.write(".gitignore", "ignored/\n"); fixture.write("ignored/file", "ignore");
        var before = fixture.snapshot(); RepositoryLocation repo = backend.locate(fixture.root); RepositoryState state = backend.state(repo);
        assertFalse(state.changes().stream().anyMatch(change -> change.path().equals("ignored/file")));
        GitChange change = state.changes().stream().filter(item -> item.path().equals(name)).findFirst().orElseThrow(); assertEquals('M', change.index()); assertEquals('M', change.worktree());
        assertEquals("one\n", backend.content(repo, GitSource.tree(a, "HEAD"), name).text()); assertEquals("two\n", backend.content(repo, GitSource.index(), name).text()); assertEquals("three\n", backend.content(repo, GitSource.working(), name).text());
        GitCommitHeader root = backend.history(repo, all(state), 0, 200).commits().getFirst(); assertEquals(a, root.id()); assertTrue(root.parents().isEmpty()); assertTrue(backend.commit(repo, root.id()).message().contains("Multiline"));
        assertEquals(name, backend.changes(repo, GitSource.empty(), GitSource.tree(a, "root")).getFirst().path());
        backend.commit(repo, a); backend.resolve(repo, "main"); backend.fingerprint(repo, state.changes());
        assertEquals(before, fixture.snapshot(), "Every repository byte, permission and entry must stay unchanged");
    }
    @Test void stagedEditsCanCancelAndRenameKeepsBothPaths() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "base\n"); String id = fixture.commit("root");
        fixture.write("a", "staged\n"); fixture.git("add", "."); fixture.write("a", "base\n"); RepositoryLocation repo = backend.locate(fixture.root);
        assertEquals(1, backend.state(repo).changes().size()); assertEquals("base\n", backend.content(repo, GitSource.working(), "a").text());
        fixture.git("reset", "--hard", "HEAD"); fixture.git("mv", "a", "new name");
        GitChange renamed = backend.state(repo).changes().getFirst(); assertEquals("a", renamed.oldPath()); assertEquals("new name", renamed.path());
        assertEquals("base\n", backend.content(repo, GitSource.tree(id, "HEAD"), renamed.oldPath()).text());
        assertEquals("base\n", backend.content(repo, GitSource.index(), renamed.path()).text());
    }
    @Test void deletedThenRecreatedPathIsOneUnionEntry() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "base"); fixture.commit("root"); fixture.git("rm", "a"); fixture.write("a", "new");
        List<GitChange> changes = backend.state(backend.locate(fixture.root)).changes(); assertEquals(1, changes.size()); assertEquals('D', changes.getFirst().index()); assertEquals('?', changes.getFirst().worktree());
    }
    @Test void graphPagingFiltersAndImmutableTips() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "0"); String root = fixture.commit("root"); fixture.git("branch", "side");
        fixture.write("a", "1"); fixture.commit("main message"); fixture.git("checkout", "side"); fixture.write("b", "side"); fixture.commit("side message"); fixture.git("checkout", "main"); fixture.git("merge", "--no-ff", "side", "-m", "merge");
        RepositoryLocation repo = backend.locate(fixture.root); RepositoryState state = backend.state(repo); HistoryQuery query = all(state);
        HistoryPage first = backend.history(repo, query, 0, 2), next = backend.history(repo, query, 2, 2); assertTrue(first.hasMore()); assertFalse(next.hasMore());
        assertEquals(2, first.commits().getFirst().parents().size()); Set<String> ids = new HashSet<>(); first.commits().forEach(c -> assertTrue(ids.add(c.id()))); next.commits().forEach(c -> assertTrue(ids.add(c.id()))); assertEquals(4, ids.size());
        HistoryQuery filtered = new HistoryQuery(query.tips(), "side message", "Example", "b"); List<GitCommitHeader> matched = backend.history(repo, filtered, 0, 200).commits(); assertEquals(1, matched.size()); assertEquals(List.of(root), matched.getFirst().parents());
        fixture.write("a", "later"); fixture.commit("later"); assertEquals(first.commits(), backend.history(repo, query, 0, 2).commits());
        assertEquals(2, backend.commit(repo, state.head()).parents().size());
        assertThrows(java.io.IOException.class, () -> backend.resolve(repo, "--all"));
    }
    @Test void linkedWorktreesDetachedHeadAndSha256() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo"), true); fixture.write("a", "value"); String id = fixture.commit("root"); Path linked = temporary.resolve("linked"); fixture.git("worktree", "add", "--detach", linked.toString(), "HEAD");
        RepositoryLocation location = backend.locate(linked); assertEquals(fixture.root.resolve(".git"), location.commonDirectory()); assertNotEquals(location.gitDirectory(), location.commonDirectory());
        RepositoryState state = backend.state(location); assertEquals(64, state.head().length()); assertEquals(id, backend.blame(location, id, "a").getFirst().commitId()); assertEquals("Detached HEAD", state.branch()); assertEquals("value", backend.content(location, GitSource.tree(id, "commit"), "a").text());
    }
    @Test void unmergedStagesAreInspectedWithoutResolving() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "base\n"); fixture.commit("root"); fixture.git("checkout", "-b", "side"); fixture.write("a", "theirs\n"); fixture.commit("side"); fixture.git("checkout", "main"); fixture.write("a", "ours\n"); fixture.commit("main");
        assertThrows(java.io.IOException.class, () -> fixture.git("merge", "side"));
        RepositoryLocation repo = backend.locate(fixture.root); assertTrue(backend.state(repo).changes().getFirst().unmerged()); var before = fixture.snapshot();
        assertEquals("base\n", backend.content(repo, GitSource.index(1), "a").text()); assertEquals("ours\n", backend.content(repo, GitSource.index(2), "a").text()); assertEquals("theirs\n", backend.content(repo, GitSource.index(3), "a").text()); assertTrue(backend.content(repo, GitSource.working(), "a").text().contains("<<<<<<<")); assertEquals(before, fixture.snapshot());
    }
    @Test void rawContentSpecialTypesAndFormatMetadata() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("utf8", "\ufeffone\r\ntwo"); fixture.write("lfs", "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 100\n"); Files.write(fixture.root.resolve("binary"), new byte[]{0, 2, 3}); Files.write(fixture.root.resolve("invalid"), new byte[]{(byte)0xff}); Files.createSymbolicLink(fixture.root.resolve("link"), Path.of("/etc/passwd")); String id = fixture.commit("types"); RepositoryLocation repo = backend.locate(fixture.root);
        GitContent content = backend.content(repo, GitSource.tree(id, "commit"), "utf8"); assertEquals("one\r\ntwo", content.text()); assertTrue(content.metadata().contains("BOM")); assertTrue(content.metadata().contains("CRLF")); assertTrue(content.metadata().contains("no final newline"));
        assertFalse(backend.content(repo, GitSource.working(), "binary").comparable()); assertFalse(backend.content(repo, GitSource.working(), "invalid").comparable()); assertTrue(backend.content(repo, GitSource.tree(id, "commit"), "lfs").explanation().contains("LFS"));
        assertEquals("/etc/passwd", backend.content(repo, GitSource.working(), "link").text()); assertEquals("/etc/passwd", backend.content(repo, GitSource.tree(id, "commit"), "link").text()); assertThrows(java.io.IOException.class, () -> backend.content(repo, GitSource.working(), "../escape"));
        fixture.git("update-index", "--add", "--cacheinfo", "160000," + id + ",module"); assertTrue(backend.content(repo, GitSource.index(), "module").metadata().contains("Submodule"));
    }
    @Test void helpersNeverRunAndMissingPromisorBlobNeverFetches() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); Path marker = temporary.resolve("helper-ran"); Path helper = temporary.resolve("helper.sh"); Files.writeString(helper, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n"); helper.toFile().setExecutable(true);
        fixture.write("a", "base\n"); fixture.write(".gitattributes", "a diff=custom\n"); String id = fixture.commit("root"); String blob = fixture.git("rev-parse", "HEAD:a");
        fixture.git("config", "core.fsmonitor", helper.toString()); fixture.git("config", "diff.external", helper.toString()); fixture.git("config", "diff.custom.textconv", helper.toString()); fixture.write("a", "changed\n");
        RepositoryLocation repo = backend.locate(fixture.root); backend.state(repo); backend.changes(repo, GitSource.tree(id, "HEAD"), GitSource.working()); backend.content(repo, GitSource.tree(id, "HEAD"), "a"); assertFalse(Files.exists(marker));
        // New adapter bypasses its successful blob cache. A promisor remote helper would leave a marker.
        Files.delete(fixture.root.resolve(".git/objects/" + blob.substring(0, 2) + "/" + blob.substring(2))); fixture.git("config", "extensions.partialClone", "origin"); fixture.git("config", "remote.origin.promisor", "true"); fixture.git("config", "remote.origin.url", "ext::" + helper); fixture.git("config", "protocol.ext.allow", "always");
        var before = fixture.snapshot(); assertThrows(java.io.IOException.class, () -> new CliGitRepository(GitConfiguration.defaults()).content(repo, GitSource.tree(id, "HEAD"), "a")); assertFalse(Files.exists(marker)); assertEquals(before, fixture.snapshot());
    }
    @Test void packagedGitCannotRecoverMissingObjectsEvenWhenRepositoryAllowsTransport() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "available only in remote\n"); String id = fixture.commit("root"); String blob = fixture.git("rev-parse", "HEAD:a");
        Path remote = temporary.resolve("remote.git"); fixture.git("clone", "--bare", fixture.root.toString(), remote.toString());
        Files.delete(fixture.root.resolve(".git/objects/" + blob.substring(0, 2) + "/" + blob.substring(2)));
        fixture.git("config", "extensions.partialClone", "origin"); fixture.git("config", "remote.origin.promisor", "true"); fixture.git("config", "remote.origin.url", remote.toUri().toString()); fixture.git("config", "protocol.file.allow", "always");
        RepositoryLocation location = backend.locate(fixture.root); var before = fixture.snapshot();
        assertThrows(java.io.IOException.class, () -> backend.content(location, GitSource.tree(id, "HEAD"), "a"));
        assertEquals(before, fixture.snapshot(), "Reading a missing object must not fetch it or change repository entries");
    }
    @Test void processAllowlistLimitsTimeoutCancellationAndEnvironment() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "hello"); fixture.commit("root");
        GitProcess runner = new GitProcess(GitConfiguration.defaults()); assertThrows(java.io.IOException.class, () -> runner.run(fixture.root, "checkout", "main"));
        GitProcess small = new GitProcess(new GitConfiguration("git", Duration.ofSeconds(2), 4, 4, 32)); assertThrows(java.io.IOException.class, () -> small.run(fixture.root, "status", "--porcelain=v2", "--branch"));
        Path slow = temporary.resolve("slow.sh"); Files.writeString(slow, "#!/bin/sh\nsleep 30\n"); slow.toFile().setExecutable(true); GitProcess timeout = new GitProcess(new GitConfiguration(slow.toString(), Duration.ofMillis(100), 100, 100, 100)); long begin = System.nanoTime(); assertThrows(java.io.IOException.class, () -> timeout.run(fixture.root, "status")); assertTrue(Duration.ofNanos(System.nanoTime() - begin).toSeconds() < 3);
        Thread.currentThread().interrupt(); try { assertThrows(java.io.InterruptedIOException.class, () -> runner.run(fixture.root, "status")); } finally { Thread.interrupted(); }
    }
    @Test void processRequestsCarrySupportedReadOnlyControlsAndSanitizeGitEnvironment() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); Path executable = temporary.resolve("inspect.sh"), record = temporary.resolve("request.txt");
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + record + "'\nprintf '%s %s %s\\n' \"$GIT_OPTIONAL_LOCKS\" \"$GIT_NO_LAZY_FETCH\" \"$GIT_TERMINAL_PROMPT\" >> '" + record + "'\n"); Files.writeString(executable, "printf 'protocols=<%s>\\n' \"${GIT_ALLOW_PROTOCOL-unset}\" >> '" + record + "'\n", StandardOpenOption.APPEND); executable.toFile().setExecutable(true);
        new GitProcess(new GitConfiguration(executable.toString(), Duration.ofSeconds(2), 10000, 100, 100)).run(fixture.root, "status");
        String request = Files.readString(record); assertFalse(request.contains("--no-lazy-fetch")); assertTrue(request.contains("--literal-pathspecs")); assertTrue(request.contains("core.fsmonitor=false")); assertTrue(request.contains("protocol.allow=never")); assertTrue(request.contains("\n0 1 0\n")); assertTrue(request.endsWith("protocols=<>\n"));
    }
    @Test void ignoredPathsAreLiteralBatchedBoundedAndRespectTrackedFiles() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("tracked.tmp", "tracked"); fixture.commit("root");
        fixture.write(".gitignore", "*.tmp\nignored/\n!keep.tmp\n");
        List<String> names = List.of("tracked.tmp", "keep.tmp", "space name.tmp", "tab\tname.tmp", "line\nname.tmp", "-dash.tmp", "literal[?]*.tmp", ":(glob)magic.tmp", "plain.txt");
        for (String name : names) if (!name.equals("tracked.tmp")) fixture.write(name, "content");
        RepositoryLocation location = backend.locate(fixture.root); var before = fixture.snapshot();
        Set<String> ignored = backend.ignored(location, names);
        assertEquals(new HashSet<>(names.subList(2, 8)), ignored);
        assertEquals(Set.of(), backend.ignored(location, List.of("plain.txt")));
        assertThrows(java.io.IOException.class, () -> backend.ignored(location, List.of("../outside")));
        assertThrows(java.io.IOException.class, () -> backend.ignored(location, java.util.Collections.nCopies(513, "a")));
        String fingerprint = backend.ignoreFingerprint(location, Set.copyOf(names)); fixture.write(".gitignore", "*.log\n");
        assertNotEquals(fingerprint, backend.ignoreFingerprint(location, Set.copyOf(names)));
        fixture.write(".gitignore", "*.tmp\nignored/\n!keep.tmp\n");
        assertEquals(before, fixture.snapshot());
    }
    @Test void configuredExcludesChangesInvalidateClassificationWithoutWritingRepository() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo"));
        Path excludes = temporary.resolve("external ignore rules"); Files.writeString(excludes, "*.cache\n");
        fixture.git("config", "core.excludesFile", excludes.toString()); fixture.write("data.cache", "data");
        RepositoryLocation location = backend.locate(fixture.root); var before = fixture.snapshot();
        String fingerprint = backend.ignoreFingerprint(location, Set.of("data.cache"));
        assertEquals(Set.of("data.cache"), backend.ignored(location, List.of("data.cache")));
        Files.writeString(excludes, "*.other\n");
        assertNotEquals(fingerprint, backend.ignoreFingerprint(location, Set.of("data.cache")));
        assertEquals(Set.of(), backend.ignored(location, List.of("data.cache")));
        assertEquals(before, fixture.snapshot());
        assertThrows(java.io.IOException.class, () -> new GitProcess(GitConfiguration.defaults()).run(fixture.root, "config", "core.excludesFile", "changed"));
    }
    @Test void replacingMetadataChangesRepositoryIdentityAndRejectsOldRequests() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo"));
        RepositoryLocation before = backend.locate(fixture.root);
        Path saved = temporary.resolve("saved-metadata"); Files.move(fixture.root.resolve(".git"), saved);
        fixture.git("init", "--initial-branch=main");
        RepositoryLocation after = backend.locate(fixture.root);
        assertNotEquals(before, after); assertEquals(before.gitDirectory(), after.gitDirectory());
        assertThrows(java.io.IOException.class, () -> backend.status(before));
        assertEquals("", backend.status(after).head());
    }
    @Test void ignoredClassificationDoesNotTraverseDirectorySymlinksOutsideRoot() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo"));
        Path outside = Files.createDirectory(temporary.resolve("outside")); Files.writeString(outside.resolve("data.tmp"), "external");
        Files.createSymbolicLink(fixture.root.resolve("link"), outside); fixture.write(".gitignore", "*.tmp\n");
        RepositoryLocation location = backend.locate(fixture.root);
        assertEquals(Set.of(), backend.ignored(location, List.of("link/data.tmp")));
        assertThrows(java.io.IOException.class, () -> backend.ignored(location, List.of(outside.toString())));
    }
    @Test void blameDoesNotRunConfiguredExternalTextConversion() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("blame-helpers")); fixture.write("a", "source\n"); fixture.write(".gitattributes", "a diff=unsafe\n"); String id = fixture.commit("root");
        Path marker = temporary.resolve("textconv-ran"); fixture.git("config", "diff.unsafe.textconv", "touch " + marker);
        RepositoryLocation location = backend.locate(fixture.root); var before = fixture.snapshot();
        assertEquals(id, backend.blame(location, id, "a").getFirst().commitId());
        assertFalse(Files.exists(marker)); assertEquals(before, fixture.snapshot());
    }
    @Test void committedEditorSourcesAndBlameArePinnedLiteralAndReadOnly() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("inspection")); String path = "tab\tline\né.txt";
        fixture.write(path, "\ufefffirst\r\nsecond\r\n"); fixture.write("empty", ""); String first = fixture.commit("Initial blame");
        fixture.git("mv", "--", path, "renamed.txt"); String second = fixture.commit("Rename");
        RepositoryLocation location = backend.locate(fixture.root); var before = fixture.snapshot();
        GitFileRevision source = backend.fileRevision(location, first, path);
        assertTrue(source.present()); assertTrue(source.content().byteOrderMark()); assertEquals("first\r\nsecond\r\n", source.content().text());
        assertFalse(backend.fileRevision(location, second, path).present());
        var blame = backend.blame(location, second, "renamed.txt");
        assertTrue(backend.blame(location, second, "empty").isEmpty());
        assertEquals(2, blame.size()); assertEquals(first, blame.getFirst().commitId()); assertEquals(path, blame.getFirst().originalPath());
        assertEquals(before, fixture.snapshot());
        fixture.git("config", "blame.ignoreRevsFile", "unavailable-ignore-revisions"); var configured = fixture.snapshot();
        assertThrows(java.io.IOException.class, () -> backend.blame(location, second, "renamed.txt"));
        assertEquals(configured, fixture.snapshot());
        assertThrows(java.io.IOException.class, () -> backend.fileRevision(location, "f".repeat(40), path));
        assertThrows(java.io.IOException.class, () -> backend.blame(location, first, "../outside"));
    }
    @Test void contentAndImmutableCacheAreBounded() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "too large for ten bytes"); String id = fixture.commit("root"); CliGitRepository small = new CliGitRepository(new GitConfiguration("git", Duration.ofSeconds(3), 4096, 10, 32)); RepositoryLocation repo = small.locate(fixture.root);
        assertFalse(small.content(repo, GitSource.tree(id, "commit"), "a").comparable()); assertFalse(small.content(repo, GitSource.working(), "a").comparable());
    }
}
