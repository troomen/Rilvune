package dev.rilvune.search.application;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Buffer effects, observed persistence and ownership cleanup are independent outcomes. */
public record ReplacementResult(List<FileResult> files, List<ReplacementProblem> problems) {
    public enum BufferEffect { NO_MATCH, NOT_ATTEMPTED, REJECTED, APPLIED, UNCERTAIN }
    public enum Persistence { NOT_APPLICABLE, SYNCHRONIZED, NEEDS_ATTENTION, FAILED }
    public record FileResult(Path path, Path currentPath, UUID documentId, int matches,
                             BufferEffect effect, Persistence persistence, boolean released) { }

    public ReplacementResult {
        files = List.copyOf(files);
        problems = List.copyOf(problems);
    }

    public int appliedMatches() {
        return files.stream().filter(file -> file.effect() == BufferEffect.APPLIED).mapToInt(FileResult::matches).sum();
    }
    public long appliedFiles() { return files.stream().filter(file -> file.effect() == BufferEffect.APPLIED).count(); }
    public boolean successful() { return problems.isEmpty(); }
    public String summary() {
        if (successful()) return appliedMatches() + " replacements applied in " + appliedFiles() + " files";
        if (files.stream().allMatch(file -> file.effect() != BufferEffect.APPLIED && file.effect() != BufferEffect.UNCERTAIN)) {
            return "No replacements were applied. " + problems.size() + " problem(s) need attention.";
        }
        long synchronizedFiles = files.stream().filter(file -> file.effect() == BufferEffect.APPLIED
                && file.persistence() == Persistence.SYNCHRONIZED).count();
        return appliedMatches() + " replacements applied in " + appliedFiles() + " files; "
                + synchronizedFiles + " synchronized. Review the file results.";
    }
    public String details() {
        return files.stream().map(file -> file.path() + " · " + file.effect() + " · " + file.persistence()
                        + (file.released() ? "" : " · cleanup pending"))
                .collect(Collectors.joining("\n")) + (problems.isEmpty() ? "" : "\n\n"
                + problems.stream().map(ReplacementProblem::description).collect(Collectors.joining("\n")));
    }
}
