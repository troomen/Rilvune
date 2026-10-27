package dev.rilvune.git.ui;
import dev.rilvune.git.domain.GitCommitHeader;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CommitGraphTest {
    private GitCommitHeader commit(String id, String... parents) { return new GitCommitHeader(id, List.of(parents), "", "", ""); }
    @Test void mergeLanesContinueAcrossPageBoundaries() {
        var commits = List.of(commit("merge", "main", "side"), commit("main", "root"), commit("side", "root"), commit("root"));
        var page = CommitGraph.layout(commits.subList(0, 2), false); var complete = CommitGraph.layout(commits, false);
        assertEquals(page.getFirst().lane(), complete.getFirst().lane()); assertEquals(page.get(1).lane(), complete.get(1).lane()); assertEquals(2, complete.getFirst().edges().size()); assertTrue(complete.get(2).lane() > 0);
    }
    @Test void filteredParentsAreBoundariesNotInventedEdges() {
        var rows = CommitGraph.layout(List.of(commit("tip", "hidden"), commit("root")), true);
        assertTrue(rows.getFirst().edges().getFirst().boundary()); assertTrue(rows.get(1).continuing().isEmpty()); assertTrue(rows.get(1).edges().isEmpty());
    }
}
