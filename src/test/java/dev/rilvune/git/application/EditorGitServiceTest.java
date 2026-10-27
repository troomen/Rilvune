package dev.rilvune.git.application;

import dev.rilvune.git.domain.*;
import dev.rilvune.git.infrastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorGitServiceTest {
    @TempDir Path temporary;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AtomicInteger baselineQueries = new AtomicInteger(), blameQueries = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
    private volatile CountDownLatch block;
    private final CountDownLatch entered = new CountDownLatch(1);
    private ProjectGitStatusService statuses;
    private EditorGitService service;
    private GitFixture fixture;
    private RepositoryLocation location;
    private String commit;
    @BeforeEach void setup() throws Exception {
        fixture = new GitFixture(temporary.resolve("repo")); fixture.write("a", "\ufeffa\r\nb\r\n"); fixture.write("b", "other\n"); commit = fixture.commit("root");
        GitRepository backend = new CliGitRepository(GitConfiguration.defaults()); location = backend.locate(fixture.root);
        GitRepository observed = (GitRepository)Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{GitRepository.class}, (proxy, method, args) -> {
            boolean inspected = method.getName().equals("fileRevision") || method.getName().equals("blame");
            if (inspected) {
                if (method.getName().equals("fileRevision")) baselineQueries.incrementAndGet(); else blameQueries.incrementAndGet();
                peak.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            }
            try {
                if (inspected && block != null) try { block.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled acquisition"); }
                try { return method.invoke(backend, args); } catch (InvocationTargetException error) { throw error.getCause(); }
            } finally { if (inspected) active.decrementAndGet(); }
        });
        statuses = new ProjectGitStatusService(observed, (repo, changed) -> () -> { }, workers, scheduler, Runnable::run,
                new ProjectGitStatusService.Configuration(Duration.ofMillis(5), Duration.ofDays(1), 20000, 32), error -> { });
        service = new EditorGitService(observed, statuses, workers, Runnable::run, new EditorGitService.Configuration(1024 * 1024, 1, 4, 4, 2), error -> { });
        statuses.project(fixture.root);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!service.status().available() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(service.status().available());
    }
    @AfterEach void close() { if (service != null) service.close(); if (statuses != null) statuses.close(); scheduler.shutdownNow(); workers.shutdownNow(); }
    @Test void sharedRequestsHaveReferenceCountedCancellationAndBoundedCache() throws Exception {
        block = new CountDownLatch(1);
        var first = service.baseline(location, commit, "a"); var second = service.baseline(location, commit, "a");
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(1, baselineQueries.get()); first.close(); block.countDown();
        EditorBaseline result = second.result().get(5, TimeUnit.SECONDS); second.close();
        assertEquals("a\nb\n", result.source().text()); assertTrue(result.source().byteOrderMark()); assertEquals("CRLF", result.source().lineEnding());
        try (var cached = service.baseline(location, commit, "a")) { assertEquals(result, cached.result().join()); }
        assertEquals(1, baselineQueries.get());
        try (var other = service.baseline(location, commit, "b")) { other.result().get(5, TimeUnit.SECONDS); }
        try (var evicted = service.baseline(location, commit, "a")) { evicted.result().get(5, TimeUnit.SECONDS); }
        assertEquals(3, baselineQueries.get()); assertTrue(peak.get() <= 2);
    }
    @Test void releasingLastConsumerCancelsAndDoesNotPoisonNextRequest() throws Exception {
        block = new CountDownLatch(1); var first = service.baseline(location, commit, "a");
        assertTrue(entered.await(2, TimeUnit.SECONDS)); first.close(); first.close();
        assertInstanceOf(CancellationException.class, assertThrows(CompletionException.class, () -> first.result().join()).getCause()); block.countDown(); block = null;
        try (var second = service.baseline(location, commit, "a")) { assertTrue(second.result().get(5, TimeUnit.SECONDS).file().present()); }
        assertEquals(2, baselineQueries.get());
    }
    @Test void committedBlameIsSharedAndSizeLimitsAreExplicit() throws Exception {
        EditorBaseline base;
        try (var acquired = service.baseline(location, commit, "a")) { base = acquired.result().get(5, TimeUnit.SECONDS); }
        try (var first = service.blame(base); var second = service.blame(base)) {
            assertEquals(first.result().get(5, TimeUnit.SECONDS), second.result().get(5, TimeUnit.SECONDS));
            assertEquals(2, first.result().join().size()); assertEquals(1, blameQueries.get());
        }
        assertThrows(IOException.class, () -> EditorGitService.requireSize("x".repeat(2 * 1024 * 1024 + 1)));
        assertThrows(IOException.class, () -> EditorGitService.requireSize("x\n".repeat(20001)));
        assertTrue(service.validate(base, "a"));
        fixture.write("b", "new\n"); fixture.commit("New baseline"); assertFalse(service.validate(base, "a"));
    }
}
