package dev.editor.editor.application;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One group decision before any removals. Feature handlers retain their own durability rules. */
public final class TabCloseCoordinator {
    private record PreparedGroup(TabClosePreparation preparation, Set<String> ids) { }
    private final Executor control;
    private final Consumer<String> remove;
    private final Map<String, CompletableFuture<Boolean>> pending = new HashMap<>();
    public TabCloseCoordinator(Executor control, Consumer<String> remove) { this.control = control; this.remove = remove; }
    public Optional<CompletableFuture<Boolean>> pending(String id) { return Optional.ofNullable(pending.get(id)); }
    public boolean busy() { return !pending.isEmpty(); }
    public CompletableFuture<Boolean> close(List<EditorTabController> controllers) {
        if (controllers.isEmpty()) return CompletableFuture.completedFuture(true);
        if (busy()) return CompletableFuture.completedFuture(false);
        Map<TabCloseHandler, List<EditorTabController>> groups = new LinkedHashMap<>();
        for (var controller : controllers) groups.computeIfAbsent(controller.closeHandler(), ignored -> new ArrayList<>()).add(controller);
        List<PreparedGroup> prepared = new ArrayList<>();
        CompletableFuture<Boolean> operation = CompletableFuture.completedFuture(true);
        for (var group : groups.entrySet()) operation = operation.thenComposeAsync(accepted -> {
            if (!accepted) return CompletableFuture.completedFuture(false);
            return group.getKey().prepare(List.copyOf(group.getValue())).thenApplyAsync(value -> {
                if (value == null) return false;
                var ids = group.getValue().stream().map(controller -> controller.model().id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
                prepared.add(new PreparedGroup(value, ids)); return true;
            }, control);
        }, control);
        operation = operation.thenComposeAsync(accepted -> {
            if (!accepted) return CompletableFuture.completedFuture(false);
            List<Throwable> failures = new ArrayList<>();
            CompletableFuture<Void> commits = CompletableFuture.completedFuture(null);
            for (var value : prepared) commits = commits.thenComposeAsync(ignored -> {
                CompletableFuture<TabCloseResult> commit;
                try { commit = value.preparation().commit(); } catch (Throwable failure) { commit = CompletableFuture.failedFuture(failure); }
                return commit.handleAsync((result, failure) -> {
                    if (failure != null) failures.add(failure);
                    if (result != null) {
                        for (String id : result.closedIds()) {
                            if (!value.ids().contains(id)) { failures.add(new IllegalStateException("Close handler returned an unrelated tab: " + id)); continue; }
                            try { remove.accept(id); } catch (Throwable cleanup) { failures.add(cleanup); }
                        }
                        if (result.failure() != null) failures.add(result.failure());
                    } else if (failure == null) failures.add(new IllegalStateException("Close handler returned no result"));
                    return (Void) null;
                }, control);
            }, control);
            return commits.thenApply(ignored -> {
                if (!failures.isEmpty()) {
                    var error = new CompletionException(failures.getFirst());
                    failures.stream().skip(1).forEach(error::addSuppressed); throw error;
                }
                return true;
            });
        }, control);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        controllers.forEach(value -> pending.put(value.model().id(), result));
        operation.whenCompleteAsync((accepted, failure) -> {
            Throwable error = failure;
            for (var value : prepared) try { value.preparation().finish(); } catch (Throwable cleanup) {
                if (error == null) error = cleanup; else error.addSuppressed(cleanup);
            }
            controllers.forEach(value -> pending.remove(value.model().id()));
            if (error == null) result.complete(accepted); else result.completeExceptionally(error);
        }, control);
        return result;
    }
}
