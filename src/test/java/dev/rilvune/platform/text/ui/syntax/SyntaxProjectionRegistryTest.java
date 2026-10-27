package dev.rilvune.platform.text.ui.syntax;

import dev.rilvune.platform.text.domain.syntax.*;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import javax.swing.text.PlainDocument;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxProjectionRegistryTest {
    @Test void sharedModelLanguageHasOneListenerJobAndFinalConsumerReleasesEverything() throws Exception {
        try (Harness h = new Harness()) {
            edt(() -> {
                PlainDocument model = model("paint \"value\""); int before = model.getDocumentListeners().length;
                SyntaxHandle first = h.open(model), second = h.open(model);
                assertEquals(before + 1, model.getDocumentListeners().length);
                assertEquals(1, h.registry.statistics().projections()); h.timers();
                assertEquals(1, h.workers.size());
                first.close(); assertEquals(1, h.registry.statistics().running());
                second.close(); assertEquals(before, model.getDocumentListeners().length);
                assertTrue(h.registry.statistics().payloadBytes() > 0, "A cancelled task still owns its reservation until actual return");
                return null;
            });
            h.work();
            edt(() -> { assertEquals(0, h.registry.statistics().payloadBytes()); assertEquals(0, h.registry.statistics().models()); return null; });
        }
    }
    @Test void editsInvalidateImmediatelyAndNewerSameLengthTextWaitsForActualCompletion() throws Exception {
        try (Harness h = new Harness()) {
            SyntaxHandle handle = edt(() -> {
                PlainDocument model = model("paint one"); SyntaxHandle view = h.open(model); h.timers();
                model.replace(6, 3, "two", null); h.timers();
                assertNull(view.result()); assertEquals(1, h.workers.size()); assertEquals(1, h.registry.statistics().running());
                return view;
            });
            h.work(); // cancelled old job completes and admits the one latest generation
            assertEquals(1, h.workers.size()); h.work();
            edt(() -> {
                assertEquals(SyntaxStatus.State.READY, handle.status().state()); assertNotNull(handle.result());
                assertEquals(1, h.registry.statistics().discarded()); handle.close();
                assertEquals(0, h.registry.statistics().payloadBytes()); return null;
            });
        }
    }
    @Test void settingsVisibilityAndOverridesNeverMutateTextOrUndoAndCanRecover() throws Exception {
        try (Harness h = new Harness()) {
            PlainDocument model = edt(() -> model("paint 42"));
            AtomicInteger edits = new AtomicInteger(); edt(() -> { model.addUndoableEditListener(event -> edits.incrementAndGet()); return null; });
            SyntaxHandle handle = edt(() -> { var view = h.open(model); h.timers(); return view; }); h.work();
            edt(() -> {
                assertNotNull(handle.result()); handle.activity(false, false); assertNull(handle.result());
                assertEquals(0, h.registry.statistics().payloadBytes()); handle.activity(true, true); h.timers(); return null;
            }); h.work();
            edt(() -> {
                handle.enabled(false); assertNull(handle.result()); assertEquals(0, model.getDocumentListeners().length);
                handle.enabled(true); h.timers(); return null;
            }); h.work();
            edt(() -> {
                assertNotNull(handle.result()); handle.selection(SyntaxSelection.explicit(SyntaxLanguageId.PLAIN));
                assertNull(handle.result()); assertEquals(SyntaxStatus.State.PLAIN, handle.status().state());
                handle.selection(SyntaxSelection.AUTO); h.timers(); return null;
            }); h.work();
            edt(() -> { assertNotNull(handle.result()); assertEquals("paint 42", model.getText(0, model.getLength())); assertEquals(0, edits.get()); handle.close(); return null; });
        }
    }
    @Test void failureAndExplicitRetryDoNotCreateRepaintRetryLoops() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true); AtomicInteger closed = new AtomicInteger();
        SyntaxContribution fixture = FixtureSyntax.contribution();
        var provider = new SyntaxLexerProvider() {
            public Set<SyntaxLanguageId> languages() { return fixture.provider().languages(); }
            public SyntaxLexer create(SyntaxLanguageId language) {
                return new SyntaxLexer() {
                    public SyntaxResult lex(SyntaxInput input, SyntaxWorkBudget budget) {
                        assertFalse(SwingUtilities.isEventDispatchThread());
                        if (broken.get()) throw new IllegalArgumentException("untrusted source must not enter UI status");
                        return fixture.provider().create(language).lex(input, budget);
                    }
                    public void close() { closed.incrementAndGet(); }
                };
            }
        };
        try (Harness h = new Harness(new SyntaxContribution(provider, fixture.languages()), limits(10, 20, 10000))) {
            SyntaxHandle handle = edt(() -> { var result = h.open(model("paint")); h.timers(); return result; }); h.work();
            edt(() -> {
                assertEquals(SyntaxStatus.State.LEXER_FAILED, handle.status().state());
                assertFalse(handle.status().explanation().contains("untrusted")); handle.activity(true, false); h.timers(); assertTrue(h.workers.isEmpty());
                broken.set(false); handle.retry(); h.timers(); return null;
            }); h.work();
            edt(() -> { assertNotNull(handle.result()); assertEquals(2, closed.get()); handle.close(); return null; });
        }
    }
    @Test void registryCapacityRecoversWhenAnotherDocumentReleasesItsProjection() throws Exception {
        try (Harness h = new Harness(FixtureSyntax.contribution(), limits(1, 10, 10000))) {
            SyntaxHandle[] views = edt(() -> {
                var first = h.open(model("paint first")); var second = h.open(model("paint second")); h.timers();
                assertEquals(SyntaxStatus.State.CAPACITY_DEFERRED, second.status().state()); return new SyntaxHandle[]{first, second};
            }); h.work();
            edt(() -> { views[0].close(); h.timers(); return null; }); h.work();
            edt(() -> { assertNotNull(views[1].result()); views[1].close(); return null; });
        }
    }
    @Test void sameTextSnapshotReplacementAndCloseReacquireRejectObsoleteOwnership() throws Exception {
        try (Harness h = new Harness()) {
            SyntaxHandle handle = edt(() -> {
                PlainDocument model = model("paint"); var first = h.open(model); h.timers(); first.close();
                var next = h.open(model); next.replaceSource(SyntaxSource.file("new.fixture")); h.timers();
                assertEquals(1, h.workers.size()); assertNull(next.result()); return next;
            }); h.work(); assertEquals(1, h.workers.size()); h.work();
            edt(() -> { assertNotNull(handle.result()); assertEquals(1, h.registry.statistics().discarded()); handle.close(); return null; });
        }
    }
    @Test void sizeLimitDoesNotCaptureTextAndShutdownDisposesCallbacksWithoutJoiningWorkers() throws Exception {
        try (Harness h = new Harness()) {
            edt(() -> {
                PlainDocument model = model("x".repeat(101)); var handle = h.open(model); h.timers();
                assertEquals(SyntaxStatus.State.LIMIT_EXCEEDED, handle.status().state()); assertTrue(h.workers.isEmpty());
                assertEquals(0, h.registry.statistics().payloadBytes()); handle.close();
                var other = h.open(model("paint")); h.timers(); h.registry.close();
                assertNull(other.result()); assertEquals(0, h.registry.statistics().handles());
                assertEquals(1, h.registry.statistics().running()); return null;
            }); h.work();
            edt(() -> { assertEquals(0, h.registry.statistics().payloadBytes()); return null; });
        }
    }
    @Test void memoryAdmissionIsBoundedAndDeferredKeysRetryWhenAnActualWorkerReturns() throws Exception {
        var policy = limits(10, 20, 2000); // A five-character job needs 1832 bytes: never admit two together.
        try (Harness h = new Harness(FixtureSyntax.contribution(), policy)) {
            SyntaxHandle[] views = edt(() -> {
                var first = h.open(model("paint")); var second = h.open(model("paint")); h.timers();
                assertEquals(1, h.registry.statistics().running()); assertEquals(1, h.workers.size());
                assertTrue(h.registry.statistics().payloadBytes() <= policy.payloadBytes());
                return new SyntaxHandle[]{first, second};
            });
            h.work(); assertEquals(1, h.workers.size()); h.work();
            edt(() -> {
                assertTrue(Arrays.stream(views).allMatch(view -> view.result() != null));
                for (var view : views) view.close(); assertEquals(0, h.registry.statistics().payloadBytes()); return null;
            });
        }
    }
    @Test void independentLanguageOverridesShareOneModelListenerButNotAProjection() throws Exception {
        var portable = FixtureSyntax.contribution(); var otherId = new SyntaxLanguageId("other");
        SyntaxContribution second = new SyntaxContribution(new SyntaxLexerProvider() {
            public Set<SyntaxLanguageId> languages() { return Set.of(otherId); }
            public SyntaxLexer create(SyntaxLanguageId language) {
                return (input, budget) -> { var builder = SyntaxResult.builder(input, budget); builder.add(0, input.text().length(), SyntaxKind.COMMENT); return builder.build(); };
            }
        }, List.of(new SyntaxLanguage(otherId, "Other", List.of())));
        try (Harness h = new Harness(List.of(portable, second), limits(10, 20, 10000))) {
            SyntaxHandle[] views = edt(() -> {
                var model = model("paint"); var first = h.open(model); var other = h.open(model); other.selection(SyntaxSelection.explicit(otherId)); h.timers();
                assertEquals(1, model.getDocumentListeners().length); assertEquals(2, h.registry.statistics().projections());
                return new SyntaxHandle[]{first, other};
            }); h.work(); h.work();
            edt(() -> { assertNotSame(views[0].result(), views[1].result()); assertEquals(SyntaxKind.COMMENT, views[1].result().kind(0)); for (var view : views) view.close(); return null; });
        }
    }
    @Test void failedAcquisitionRollsBackTheListenerHandleAndProjection() throws Exception {
        try (Harness h = new Harness()) {
            edt(() -> {
                var model = model("paint");
                assertThrows(IllegalStateException.class, () -> h.registry.acquire(model, SyntaxSource.file("sample.fixture"), () -> { throw new IllegalStateException("failed presentation"); }));
                assertEquals(0, model.getDocumentListeners().length); assertEquals(0, h.registry.statistics().models()); assertEquals(0, h.registry.statistics().handles());
                return null;
            });
        }
    }
    private static SyntaxLimits limits(int entries, int handles, long bytes) {
        return new SyntaxLimits(100, 10, 50, 32, 2, entries, handles, bytes, Duration.ZERO, Duration.ofSeconds(1));
    }
    private static PlainDocument model(String text) throws Exception { PlainDocument model = new PlainDocument(); model.insertString(0, text, null); return model; }
    private static final class Harness implements AutoCloseable {
        final Queue<Runnable> workers = new ArrayDeque<>(), timers = new ArrayDeque<>();
        final SyntaxProjectionRegistry registry;
        Harness() { this(FixtureSyntax.contribution(), limits(10, 20, 10000)); }
        Harness(SyntaxContribution contribution, SyntaxLimits limits) {
            this(List.of(contribution), limits);
        }
        Harness(List<SyntaxContribution> contributions, SyntaxLimits limits) {
            registry = new SyntaxProjectionRegistry(new SyntaxCatalog(contributions), limits, workers::add, () -> 0, (delay, task) -> {
                AtomicBoolean cancelled = new AtomicBoolean(); timers.add(() -> { if (!cancelled.get()) task.run(); }); return () -> cancelled.set(true);
            });
        }
        SyntaxHandle open(PlainDocument model) { var handle = registry.acquire(model, SyntaxSource.file("sample.fixture"), () -> { }); handle.activity(true, false); return handle; }
        void timers() { while (!timers.isEmpty()) timers.remove().run(); }
        void work() throws Exception { assertFalse(workers.isEmpty()); workers.remove().run(); edt(() -> null); }
        public void close() throws Exception { edt(() -> { registry.close(); return null; }); }
    }
    private static <T> T edt(Callable<T> task) throws Exception { FutureTask<T> future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get(); }
}
