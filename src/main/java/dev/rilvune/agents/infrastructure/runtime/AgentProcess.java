package dev.rilvune.agents.infrastructure.runtime;

import java.io.IOException;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Owns only processes created by this adapter. No shell interpolation, global PATH installation or shared daemon. */
public final class AgentProcess implements AutoCloseable {
    private final Process process;
    private final Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
    private CompletableFuture<Void> pipesClosed;
    public AgentProcess(Path executable, Path project, List<String> arguments, Map<String, String> environment) throws IOException {
        List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()); builder.environment().putAll(environment);
        process = builder.start();
        Thread.ofVirtual().name("agent-stderr").start(() -> { try (var error = process.getErrorStream()) { error.transferTo(java.io.OutputStream.nullOutputStream()); } catch (IOException ignored) { } });
    }
    public Process process() { return process; }
    public boolean alive() { return process.isAlive(); }
    @Override public synchronized void close() {
        rememberDescendants();
        List<ProcessHandle> owned = ownedProcesses();
        try {
            // Process.destroy() also closes streams, which can block behind a writer before any timeout is reached.
            // ProcessHandle sends only the termination signal, keeping escalation independent of pipe locks.
            owned.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
            try { exited(owned).get(2, TimeUnit.SECONDS); }
            catch (TimeoutException graceExpired) {
                rememberDescendants(); owned = ownedProcesses();
                owned.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                exited(owned).get(3, TimeUnit.SECONDS);
            }
            closePipes().get(3, TimeUnit.SECONDS);
            descendants.clear();
        } catch (InterruptedException interrupted) {
            rememberDescendants(); ownedProcesses().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while stopping the agent", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("The agent process or its pipes did not close; retry closing the project", failure);
        }
    }
    private void rememberDescendants() { process.descendants().forEach(child -> descendants.put(child.pid(), child)); }
    private List<ProcessHandle> ownedProcesses() {
        List<ProcessHandle> owned = new ArrayList<>(List.copyOf(descendants.values()).reversed());
        owned.add(process.toHandle()); return owned;
    }
    private static CompletableFuture<Void> exited(List<ProcessHandle> owned) {
        return CompletableFuture.allOf(owned.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::onExit).toArray(CompletableFuture[]::new));
    }
    private CompletableFuture<Void> closePipes() {
        if (pipesClosed != null && !pipesClosed.isCompletedExceptionally()) return pipesClosed;
        var done = new CompletableFuture<Void>(); pipesClosed = done;
        // One owned cleanup task, reused on retry: a delayed stream close must not accumulate replacement threads.
        List<Closeable> pipes = List.of(process.getOutputStream(), process.getInputStream(), process.getErrorStream());
        Thread.ofVirtual().name("agent-pipes-close").start(() -> {
            try {
                for (Closeable pipe : pipes) try { pipe.close(); } catch (IOException alreadyTerminated) { }
                done.complete(null);
            } catch (RuntimeException failure) { done.completeExceptionally(failure); }
        });
        return done;
    }
}
