package dev.editor.agents.infrastructure.runtime;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class AgentProcessTest {
    @TempDir Path project;

    @Test @Timeout(15)
    void closingRuntimeAwaitsBothParentAndItsToolSubprocess() throws Exception {
        Path childPid = project.resolve("child.pid");
        try (var process = new AgentProcess(java(), project, List.of("-cp", System.getProperty("java.class.path"),
                ProcessTreeFixture.class.getName(), childPid.toString()), Map.of())) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(childPid) || Files.size(childPid) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(childPid))).orElseThrow();
            assertTrue(child.isAlive()); process.close();
            assertFalse(process.alive()); assertFalse(child.isAlive()); process.close();
        }
    }

    @Test @Timeout(20)
    void closingCanEscalateEvenWhenAWriterIsBlockedAndTheChildDoesNotFinishGracefulShutdown() throws Exception {
        var process = new AgentProcess(java(), project, List.of("-cp", System.getProperty("java.class.path"), BlockedInputFixture.class.getName()), Map.of());
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Thread writer = null; Future<?> closing = null;
        try {
            assertEquals("ready\n", new String(process.process().getInputStream().readNBytes(6), StandardCharsets.UTF_8));
            Assumptions.assumeTrue(process.process().supportsNormalTermination());
            writer = Thread.ofPlatform().name("agent-blocked-pipe-test").start(() -> {
                try { process.process().getOutputStream().write(new byte[4 * 1024 * 1024]); }
                catch (IOException expectedAfterTermination) { }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Arrays.stream(writer.getStackTrace()).noneMatch(frame -> frame.getClassName().equals("java.io.FileOutputStream") && frame.getMethodName().equals("writeBytes"))) {
                if (System.nanoTime() >= deadline) fail("Fixture writer did not block in the process pipe");
                Thread.sleep(5);
            }
            closing = workers.submit(process::close);
            closing.get(8, TimeUnit.SECONDS);
            assertFalse(process.alive()); writer.join(3000); assertFalse(writer.isAlive());
            process.close();
        } finally {
            // Signal via ProcessHandle: Process.destroy itself can block on the pipe that this test deliberately fills.
            process.process().toHandle().destroyForcibly(); process.process().toHandle().onExit().get(3, TimeUnit.SECONDS);
            if (writer != null) writer.join(3000);
            if (closing != null) try { closing.get(3, TimeUnit.SECONDS); } catch (ExecutionException ignored) { }
            workers.shutdownNow(); process.close();
        }
    }

    @Test @Timeout(20)
    void delayedPipeCleanupIsBoundedAndCanFinishOnRetry() throws Exception {
        var process = new AgentProcess(java(), project, List.of("-cp", System.getProperty("java.class.path"), IdleFixture.class.getName()), Map.of());
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Thread holder = null;
        try {
            assertEquals("ready\n", new String(process.process().getInputStream().readNBytes(6), StandardCharsets.UTF_8));
            holder = Thread.ofPlatform().name("agent-delayed-pipe-close-test").start(() -> {
                synchronized (process.process().getOutputStream()) {
                    held.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(held.await(3, TimeUnit.SECONDS));
            var closing = workers.submit(process::close);
            var failure = assertThrows(ExecutionException.class, () -> closing.get(9, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertInstanceOf(TimeoutException.class, failure.getCause().getCause());
            assertFalse(process.alive()); release.countDown(); holder.join(3000);
            process.close(); process.close();
        } finally {
            release.countDown();
            process.process().toHandle().destroyForcibly(); process.process().toHandle().onExit().get(3, TimeUnit.SECONDS);
            if (holder != null) holder.join(3000);
            workers.shutdownNow(); process.close();
        }
    }

    private static Path java() { return Path.of(System.getProperty("java.home"), "bin", "java"); }

    public static final class ProcessTreeFixture {
        public static void main(String[] arguments) throws Exception {
            if (!arguments[0].equals("sleep")) {
                Process child = new ProcessBuilder(java().toString(), "-cp", System.getProperty("java.class.path"),
                        ProcessTreeFixture.class.getName(), "sleep").start();
                Files.writeString(Path.of(arguments[0]), Long.toString(child.pid()));
            }
            Thread.sleep(60_000);
        }
    }
    public static final class BlockedInputFixture {
        public static void main(String[] arguments) throws Exception {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (;;) java.util.concurrent.locks.LockSupport.park();
            }));
            System.out.print("ready\n"); System.out.flush();
            Thread.sleep(60_000); // Deliberately never consume stdin; shutdown must not depend on a cooperative reader.
        }
    }
    public static final class IdleFixture {
        public static void main(String[] arguments) throws Exception {
            System.out.print("ready\n"); System.out.flush(); Thread.sleep(60_000);
        }
    }
}
