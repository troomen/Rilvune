package dev.rilvune.search.application;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/** Aggregates the primary operation failure and every independent cleanup failure. */
public final class ReplacementFailure extends IOException {
    private final List<ReplacementProblem> problems;

    public ReplacementFailure(List<ReplacementProblem> problems) {
        super(problems.stream().map(ReplacementProblem::description).collect(Collectors.joining("\n")),
                problems.isEmpty() ? null : problems.getFirst().cause());
        this.problems = List.copyOf(problems);
        for (int index = 1; index < problems.size(); index++) {
            var problem = problems.get(index);
            addSuppressed(new IOException(problem.description(), problem.cause()));
        }
    }

    public List<ReplacementProblem> problems() { return problems; }
}
