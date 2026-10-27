package dev.rilvune.git.infrastructure;

import dev.rilvune.git.domain.*;
import dev.rilvune.git.domain.FileHistoryQuery.Lines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileHistoryRepositoryTest {
    @TempDir Path directory;
    private final CliGitRepository repository = new CliGitRepository(GitConfiguration.defaults());

    @Test void fileHistoryFollowsRenamesAndDeletionAcrossPagesWithoutChangingRepository() throws Exception {
        var fixture = new GitFixture(directory);
        String old = "old\t \"é\\\n.txt", current = "new :*é.txt";
        fixture.write(old, "header\nselected\nfooter\n"); String initial = fixture.commit("Initial file");
        fixture.write("unrelated", "other"); fixture.commit("Unrelated change");
        fixture.git("mv", "--", old, current); String renamed = fixture.commit("Rename file");
        fixture.write(current, "header\nSELECTED\nfooter\n"); String changed = fixture.commit("Change file");
        fixture.git("rm", "--", current); String deleted = fixture.commit("Delete file");
        fixture.git("config", "log.showRoot", "false");
        var location = repository.locate(directory); var before = fixture.snapshot();
        var query = new FileHistoryQuery(deleted, current, null);
        var page = repository.fileHistory(location, query, 0, 2);
        assertEquals(List.of(deleted, changed), page.entries().stream().map(entry -> entry.commit().id()).toList());
        assertTrue(page.hasMore());
        var older = repository.fileHistory(location, query, 2, 2);
        assertEquals(List.of(renamed, initial), older.entries().stream().map(entry -> entry.commit().id()).toList());
        assertFalse(older.hasMore());
        assertEquals(old, older.entries().getFirst().change().oldPath());
        assertEquals(current, older.entries().getFirst().change().path());
        assertEquals(old, older.entries().getLast().change().path());
        assertEquals(before, fixture.snapshot());
    }

    @Test void selectionTracksHistoricalCoordinatesAndPathsAcrossInsertionAndRename() throws Exception {
        var fixture = new GitFixture(directory);
        fixture.write("old.txt", "header\nselected old\nfooter\n"); String initial = fixture.commit("Initial");
        fixture.write("old.txt", "header\nselected new\nfooter\n"); String changed = fixture.commit("Change selected line");
        fixture.write("old.txt", "intro\nheader\nselected new\nfooter\n"); fixture.commit("Insert outside selection");
        fixture.git("mv", "old.txt", "new.txt"); fixture.commit("Rename");
        fixture.write("new.txt", "intro\nheader\nselected new\nchanged footer\n"); String head = fixture.commit("Change outside selection");
        var location = repository.locate(directory); var before = fixture.snapshot();
        var page = repository.fileHistory(location, new FileHistoryQuery(head, "new.txt", new Lines(2, 3)), 0, 100);
        assertEquals(List.of(changed, initial), page.entries().stream().map(entry -> entry.commit().id()).toList());
        assertEquals(new Lines(1, 2), page.entries().getFirst().after());
        assertEquals("old.txt", page.entries().getFirst().change().path());
        var row = page.entries().getFirst();
        assertTrue(repository.content(location, GitSource.tree(changed, "after"), row.change().path()).text().contains("selected new"));
        assertTrue(repository.content(location, GitSource.tree(row.commit().parents().getFirst(), "before"), row.change().oldPath()).text().contains("selected old"));
        assertEquals(before, fixture.snapshot());
    }

    @Test void selectionUsesLiteralQuotedUnicodeAndWhitespacePaths() throws Exception {
        var fixture = new GitFixture(directory);
        for (String path : List.of("space é.txt", "colon:name.txt", "tab\tquote\"newline\n.txt", "-dash.txt", ":(glob)*.txt")) fixture.write(path, "one\ntwo\n");
        String head = fixture.commit("Create literal paths");
        fixture.git("config", "diff.noprefix", "true");
        var location = repository.locate(directory);
        for (String path : List.of("space é.txt", "colon:name.txt", "tab\tquote\"newline\n.txt", "-dash.txt", ":(glob)*.txt")) {
            var page = repository.fileHistory(location, new FileHistoryQuery(head, path, new Lines(1, 2)), 0, 10);
            assertEquals(1, page.entries().size(), path);
            assertEquals(path, page.entries().getFirst().change().path());
            assertEquals(new Lines(1, 2), page.entries().getFirst().after());
        }
    }

    @Test void immutableHistoryDoesNotMoveWhenHeadAdvancesAndRejectsInvalidRequests() throws Exception {
        var fixture = new GitFixture(directory);
        fixture.write("file", "one\n"); String first = fixture.commit("First");
        var query = new FileHistoryQuery(first, "file", null);
        fixture.write("file", "two\n"); fixture.commit("Second");
        var location = repository.locate(directory);
        assertEquals(List.of(first), repository.fileHistory(location, query, 0, 10).entries().stream().map(entry -> entry.commit().id()).toList());
        assertTrue(repository.fileHistory(location, new FileHistoryQuery(first, "absent", null), 0, 10).entries().isEmpty());
        assertThrows(java.io.IOException.class, () -> repository.fileHistory(location, query, -1, 10));
        assertThrows(java.io.IOException.class, () -> repository.fileHistory(location, new FileHistoryQuery(first, "../outside", null), 0, 10));
    }
    @Test void selectionPagesUseStableCoordinatesAndSha256CommitIds() throws Exception {
        var fixture = new GitFixture(directory, true);
        fixture.write("file", "first\n"); String initial = fixture.commit("Initial");
        fixture.write("file", "second\n"); String changed = fixture.commit("Change");
        fixture.write("file", "third\n"); String head = fixture.commit("Change again");
        var location = repository.locate(directory); var query = new FileHistoryQuery(head, "file", new Lines(0, 1));
        assertEquals(64, head.length());
        var first = repository.fileHistory(location, query, 0, 1);
        assertEquals(head, first.entries().getFirst().commit().id()); assertTrue(first.hasMore());
        var second = repository.fileHistory(location, query, 1, 1);
        assertEquals(changed, second.entries().getFirst().commit().id()); assertTrue(second.hasMore());
        var last = repository.fileHistory(location, query, 2, 1);
        assertEquals(initial, last.entries().getFirst().commit().id()); assertFalse(last.hasMore());
    }

    @Test void mergeRevisionComparesTheFileAgainstItsFirstParent() throws Exception {
        var fixture = new GitFixture(directory);
        fixture.write("file", "before\n"); fixture.commit("Initial");
        fixture.git("checkout", "-b", "feature"); fixture.write("file", "feature\n"); String feature = fixture.commit("Feature");
        fixture.git("checkout", "main"); fixture.write("other", "main\n"); fixture.commit("Main");
        fixture.git("merge", "--no-ff", "feature", "-m", "Merge feature");
        String head = fixture.git("rev-parse", "HEAD"); var location = repository.locate(directory);
        for (Lines selection : new Lines[] {null, new Lines(0, 1)}) {
            var page = repository.fileHistory(location, new FileHistoryQuery(head, "file", selection), 0, 10);
            assertFalse(page.entries().isEmpty());
            var row = page.entries().getFirst();
            assertEquals(selection == null ? head : feature, row.commit().id());
            assertEquals("before\n", repository.content(location, GitSource.tree(row.commit().parents().getFirst(), "parent"), row.change().oldPath()).text());
            assertEquals("feature\n", repository.content(location, GitSource.tree(head, "commit"), row.change().path()).text());
        }
    }
    @Test void mergeResolutionOffersFileHistoryInsteadOfGuessingLineParentage() throws Exception {
        var fixture = new GitFixture(directory);
        fixture.write("file", "header\nbase\nfooter\n"); fixture.commit("Initial");
        fixture.git("checkout", "-b", "feature"); fixture.write("file", "header\nfeature\nfooter\n"); fixture.commit("Feature");
        fixture.git("checkout", "main"); fixture.write("file", "header\nmain\nfooter\n"); fixture.commit("Main");
        assertThrows(java.io.IOException.class, () -> fixture.git("merge", "feature"));
        fixture.write("file", "header\nresolved\nfooter\n"); String head = fixture.commit("Resolve merge");
        var location = repository.locate(directory);
        var failure = assertThrows(java.io.IOException.class, () -> repository.fileHistory(location,
                new FileHistoryQuery(head, "file", new Lines(1, 2)), 0, 10));
        assertTrue(failure.getMessage().contains("use file history"));
        assertEquals(head, repository.fileHistory(location, new FileHistoryQuery(head, "file", null), 0, 10).entries().getFirst().commit().id());
    }
}
