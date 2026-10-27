package dev.editor.git.infrastructure;

import dev.editor.git.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHistoryPerformanceTest {
    @TempDir Path temporary;
    @Test void pagesSubstantialHistoryAndReportsMeasuredQueryTime() throws Exception {
        GitFixture fixture = new GitFixture(temporary.resolve("repo"));
        StringBuilder importData = new StringBuilder();
        for (int i = 1; i <= 1200; i++) {
            String message = "Fixture commit " + i + "\n", content = "value " + i + "\n";
            importData.append("commit refs/heads/main\nmark :").append(i).append("\nauthor Author <a@example.test> ").append(1700000000L + i).append(" +0000\ncommitter Author <a@example.test> ").append(1700000000L + i).append(" +0000\ndata ").append(message.length()).append('\n').append(message);
            if (i > 1) importData.append("from :").append(i - 1).append('\n');
            importData.append("M 100644 inline a\ndata ").append(content.length()).append('\n').append(content).append('\n');
        }
        Process process = new ProcessBuilder("git", "fast-import", "--quiet").directory(fixture.root.toFile()).redirectErrorStream(true).start();
        try (var input = process.getOutputStream()) { input.write(importData.toString().getBytes(StandardCharsets.UTF_8)); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.waitFor(), output);
        var backend = new CliGitRepository(GitConfiguration.defaults()); var repo = backend.locate(fixture.root); String head = backend.resolve(repo, "HEAD");
        var query = new HistoryQuery(List.of(head), "", "", ""); long started = System.nanoTime(); Set<String> ids = new HashSet<>();
        for (int offset = 0; offset < 1200; offset += 200) { var page = backend.history(repo, query, offset, 200); assertEquals(200, page.commits().size()); for (GitCommitHeader commit : page.commits()) assertTrue(ids.add(commit.id())); assertEquals(offset < 1000, page.hasMore()); }
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Files.createDirectories(Path.of("build/metrics")); Files.writeString(Path.of("build/metrics/git-history.txt"), "1,200 fixture commits; 6 pages × 200 rows; total native history query time: " + elapsed + " ms\n");
        assertEquals(1200, ids.size()); assertTrue(elapsed < 20000, "bounded queries must finish within the configured timeout on a fixture");
    }
}
