package dev.editor.git.ui;

import dev.editor.git.domain.GitCommitHeader;
import java.util.*;

/** Lane geometry uses original parent IDs. A boundary is never rewritten into a visible ancestor. */
final class CommitGraph {
    record Edge(int from, int to, boolean boundary) { }
    record Row(int lane, int lanes, List<Edge> edges, List<Integer> continuing) { }
    static List<Row> layout(List<GitCommitHeader> commits, boolean filtered) {
        Set<String> visible = new HashSet<>(); commits.forEach(commit -> visible.add(commit.id()));
        List<String> lanes = new ArrayList<>(); List<Row> rows = new ArrayList<>();
        for (GitCommitHeader commit : commits) {
            int lane = lanes.indexOf(commit.id()); if (lane < 0) { lane = lanes.size(); lanes.add(commit.id()); }
            List<Integer> continuing = new ArrayList<>();
            for (int i = 0; i < lanes.size(); i++) if (i != lane && !lanes.get(i).isEmpty()) continuing.add(i);
            int width = lanes.size(); lanes.set(lane, ""); List<Edge> edges = new ArrayList<>();
            for (String parent : commit.parents()) {
                boolean boundary = filtered && !visible.contains(parent);
                if (boundary) { edges.add(new Edge(lane, lane, true)); continue; }
                int target = lanes.indexOf(parent);
                if (target < 0) {
                    target = lanes.indexOf("");
                    if (target < 0 && lanes.size() >= 64) { edges.add(new Edge(lane, lane, true)); continue; }
                    if (target < 0) { target = lanes.size(); lanes.add(parent); } else lanes.set(target, parent);
                }
                edges.add(new Edge(lane, target, !visible.contains(parent))); width = Math.max(width, target + 1);
            }
            // Keep holes for stable columns across pages, reuse them for new branches.
            while (!lanes.isEmpty() && lanes.getLast().isEmpty()) lanes.removeLast();
            rows.add(new Row(lane, width, List.copyOf(edges), List.copyOf(continuing)));
        }
        return List.copyOf(rows);
    }
}
