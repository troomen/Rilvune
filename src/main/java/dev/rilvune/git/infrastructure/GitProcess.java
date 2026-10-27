package dev.rilvune.git.infrastructure;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Package-private allowlisted runner. Never expose command execution through the repository port. */
final class GitProcess {
    record Result(int exit, byte[] output, String error) { }
    private static final Set<String> QUERIES = Set.of("rev-parse", "symbolic-ref", "for-each-ref", "status", "log", "show", "diff", "diff-tree", "ls-tree", "ls-files", "cat-file", "check-ignore", "blame");
    private final GitConfiguration configuration;
    GitProcess(GitConfiguration configuration) { this.configuration = configuration; }
    Result run(Path root, String... arguments) throws IOException { return run(root, List.of(arguments)); }
    Result run(Path root, List<String> arguments) throws IOException { return run(root, arguments, new byte[0]); }
    Result run(Path root, List<String> arguments, byte[] input) throws IOException {
        if (arguments.isEmpty() || !(QUERIES.contains(arguments.getFirst())
                || arguments.equals(List.of("config", "--path", "--get", "core.excludesFile")))) throw new IOException("Not a Git query");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Git request cancelled");
        List<String> command = new ArrayList<>(List.of(configuration.executable(), "--no-pager",
                "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false", "-c", "gc.auto=0",
                "-c", "maintenance.auto=false", "-c", "color.ui=false", "-c", "core.quotePath=false",
                "-c", "core.hooksPath=/dev/null", "-c", "protocol.allow=never", "-c", "log.showSignature=false",
                "-c", "diff.renames=true", "-c", "diff.renameLimit=1000"));
        // check-ignore consumes literal pathname input and rejects the global pathspec-magic flag.
        if (!arguments.getFirst().equals("check-ignore")) command.add("--literal-pathspecs");
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> key.startsWith("GIT_") || key.equals("SSH_ASKPASS"));
        // No transport is allowed, even if repository config explicitly enables a protocol.
        // Newer Git also honors NO_LAZY_FETCH; older packaged versions safely ignore it.
        environment.putAll(Map.of("GIT_OPTIONAL_LOCKS", "0", "GIT_NO_LAZY_FETCH", "1", "GIT_ALLOW_PROTOCOL", "", "GIT_TERMINAL_PROMPT", "0",
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_PAGER", "cat", "LC_ALL", "C.UTF-8"));
        Process process = builder.start();
        try (ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> written = drains.submit(() -> { try (OutputStream stream = process.getOutputStream()) { stream.write(input); } return null; });
            Future<byte[]> output = drains.submit(() -> read(process.getInputStream(), configuration.outputLimit(), process));
            Future<byte[]> errors = drains.submit(() -> read(process.getErrorStream(), 64 * 1024, process));
            try {
                if (!process.waitFor(configuration.timeout().toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Git query timed out");
                written.get();
                return new Result(process.exitValue(), output.get(), new String(errors.get(), StandardCharsets.UTF_8).strip());
            } catch (InterruptedException failure) {
                terminate(process); Thread.currentThread().interrupt(); throw new InterruptedIOException("Git request cancelled");
            } catch (ExecutionException failure) { throw new IOException(failure.getCause().getMessage(), failure.getCause()); }
            finally { terminate(process); }
        }
    }
    private byte[] read(InputStream stream, int limit, Process process) throws IOException {
        try (stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (bytes.size() + count > limit) { terminate(process); throw new IOException("Git output exceeds the configured limit"); }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }
    private static void terminate(Process process) { process.descendants().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly(); }
}
