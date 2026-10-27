package dev.editor.search.application;

import dev.editor.document.application.DocumentHandle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Owns acquisitions before any executor handoff; failed releases remain retryable. */
final class ReplacementHandles {
    private final Map<DocumentHandle, Path> handles = new LinkedHashMap<>();
    private CompletableFuture<Void> pending;

    synchronized void add(DocumentHandle handle, Path requestedPath) { handles.put(handle, requestedPath); }

    CompletableFuture<Void> release(DocumentHandle handle) {
        try {
            return handle.release().thenRun(() -> { synchronized (this) { handles.remove(handle); } });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    CompletableFuture<Void> releaseAll() {
        CompletableFuture<Void> completion;
        Map<DocumentHandle, Path> unresolved;
        synchronized (this) {
            if (pending != null) return pending.copy();
            completion = new CompletableFuture<>();
            pending = completion;
            unresolved = new LinkedHashMap<>(handles);
        }
        List<ReplacementProblem> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> attempts = unresolved.keySet().stream().map(handle -> release(handle).handle((ignored, failure) -> {
            if (failure != null) {
                var snapshot = handle.session().snapshot();
                problems.add(new ReplacementProblem(ReplacementProblem.Phase.CLEANUP, unresolved.get(handle), snapshot.path(), snapshot.id(), failure));
            }
            return (Void) null;
        })).toList();
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            synchronized (this) { pending = null; }
            if (problems.isEmpty()) completion.complete(null);
            else completion.completeExceptionally(new ReplacementFailure(problems));
        });
        return completion.copy();
    }
}
