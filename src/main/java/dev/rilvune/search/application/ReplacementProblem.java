package dev.rilvune.search.application;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** A failure keeps its operation phase and file context, including after a path changes. */
public record ReplacementProblem(Phase phase, Path path, Path currentPath, UUID documentId, Throwable cause) {
    public enum Phase { PREPARATION, PREFLIGHT, APPLICATION, PERSISTENCE, CLEANUP }

    public ReplacementProblem {
        Objects.requireNonNull(phase);
        cause = unwrap(Objects.requireNonNull(cause));
    }

    public String description() {
        String location = path == null ? "" : " · " + path;
        if (currentPath != null && !currentPath.equals(path)) location += " (now " + currentPath + ")";
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return phase + location + ": " + message;
    }

    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
