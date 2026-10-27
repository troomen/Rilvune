package dev.rilvune.platform.text.ui.syntax;

import dev.rilvune.platform.text.domain.syntax.*;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import static dev.rilvune.platform.text.ui.syntax.SyntaxStatus.State.*;

/** Window-owned, EDT-confined admission and model/language sharing. Workers never read Swing models. */
public final class SyntaxProjectionRegistry implements SyntaxSupport, AutoCloseable {
    @FunctionalInterface interface Debouncer { Runnable schedule(int milliseconds, Runnable task); }
    public record Statistics(int models, int projections, int handles, int running, int pending, long payloadBytes,
                             long completed, long discarded, long lastCaptureNanos, long lastLexNanos, long lastPublicationNanos) { }
    private final SyntaxCatalog catalog;
    private final SyntaxLanguageResolver resolver;
    private final SyntaxLimits limits;
    private final Executor workers;
    private final LongSupplier clock;
    private final Debouncer debounce;
    private final IdentityHashMap<Document, Model> models = new IdentityHashMap<>();
    private final Map<Long, Running> running = new HashMap<>();
    private long sequence, payload, completed, discarded;
    private long lastCaptureNanos, lastLexNanos, lastPublicationNanos;
    private int projections, handles;
    private boolean closed, pumping;

    public SyntaxProjectionRegistry(SyntaxCatalog catalog, SyntaxLimits limits, Executor workers) {
        this(catalog, limits, workers, System::nanoTime, (delay, action) -> {
            Timer timer = new Timer(delay, event -> action.run()); timer.setRepeats(false); timer.start(); return timer::stop;
        });
    }
    SyntaxProjectionRegistry(SyntaxCatalog catalog, SyntaxLimits limits, Executor workers, LongSupplier clock, Debouncer debounce) {
        this.catalog = Objects.requireNonNull(catalog); resolver = new SyntaxLanguageResolver(catalog);
        this.limits = Objects.requireNonNull(limits); this.workers = Objects.requireNonNull(workers);
        this.clock = Objects.requireNonNull(clock); this.debounce = Objects.requireNonNull(debounce);
    }
    @Override public SyntaxHandle acquire(Document document, SyntaxSource source, Runnable changed) {
        edt(); Objects.requireNonNull(document); Objects.requireNonNull(source); Objects.requireNonNull(changed);
        if (closed) throw new IllegalStateException("Syntax support is closed");
        if (!source.complete()) return SyntaxSupport.unavailable("Derived or partial source · plain text");
        if (handles >= limits.handles()) return SyntaxSupport.unavailable("Syntax view capacity reached · plain text");
        Model model = models.computeIfAbsent(document, Model::new);
        Handle handle = new Handle(model, source, changed); model.owners.add(handle); handles++;
        try { reconcile(model); return handle; }
        catch (RuntimeException failure) {
            try { handle.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public Statistics statistics() {
        edt(); return new Statistics(models.size(), projections, handles, running.size(), (int) entries().stream().filter(entry -> entry.ready || entry.cancelTimer != null).count(),
                payload, completed, discarded, lastCaptureNanos, lastLexNanos, lastPublicationNanos);
    }

    private final class Model {
        final Document document;
        final List<Handle> owners = new ArrayList<>();
        final Map<SyntaxLanguageId, Projection> entries = new LinkedHashMap<>();
        final DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { edited(); }
            public void removeUpdate(DocumentEvent event) { edited(); }
            public void changedUpdate(DocumentEvent event) { }
            private void edited() {
                edt(); generation++;
                for (var entry : entries.values()) invalidate(entry);
                reconcile(Model.this); pump();
            }
        };
        long generation;
        boolean attached;
        Model(Document document) { this.document = document; }
    }
    private final class Projection {
        final Model model;
        final SyntaxLanguageId language;
        SyntaxResult result;
        SyntaxStatus status = SyntaxStatus.of(PENDING, "Waiting for syntax highlighting");
        Running job;
        Runnable cancelTimer;
        boolean ready, blocked;
        long request, ticket;
        Projection(Model model, SyntaxLanguageId language) { this.model = model; this.language = language; }
        boolean owned() { return model.owners.stream().anyMatch(owner -> owner.entry == this); }
        boolean visible() { return model.owners.stream().anyMatch(owner -> owner.entry == this && owner.enabled && owner.visible); }
        boolean focused() { return model.owners.stream().anyMatch(owner -> owner.entry == this && owner.enabled && owner.visible && owner.focused); }
    }
    private record Running(long id, Projection entry, long generation, long request, long reserved, AtomicBoolean cancelled) { }

    private final class Handle implements SyntaxHandle {
        final Model model;
        final Runnable changed;
        SyntaxSource source;
        SyntaxSelection selection = SyntaxSelection.AUTO;
        SyntaxLanguageId language = SyntaxLanguageId.PLAIN;
        Projection entry;
        boolean enabled = true, visible, focused, disposed;
        Handle(Model model, SyntaxSource source, Runnable changed) { this.model = model; this.source = source; this.changed = changed; }
        public SyntaxResult result() { edt(); return disposed || !enabled || !visible || entry == null ? null : entry.result; }
        public SyntaxStatus status() {
            edt();
            if (disposed || !enabled) return SyntaxStatus.of(DISABLED, "Syntax highlighting is disabled");
            if (!source.complete()) return SyntaxStatus.of(PLAIN, "Derived or partial source · plain text");
            if (Boolean.TRUE.equals(model.document.getProperty("i18n"))) return SyntaxStatus.of(PLAIN, "Native complex-text layout · plain text");
            if (language.equals(SyntaxLanguageId.PLAIN)) return SyntaxStatus.of(PLAIN, "Plain text");
            return entry == null ? SyntaxStatus.of(CAPACITY_DEFERRED, "Syntax projection capacity reached") : entry.status;
        }
        public SyntaxLanguageId language() { edt(); return language; }
        public SyntaxSelection selection() { edt(); return selection; }
        public void source(SyntaxSource value) { edt(); if (!disposed && !source.equals(value)) { source = Objects.requireNonNull(value); reconcile(model); pump(); } }
        public void replaceSource(SyntaxSource value) {
            edt(); if (disposed) return;
            source = Objects.requireNonNull(value); selection = SyntaxSelection.AUTO;
            model.generation++; for (var projection : model.entries.values()) invalidate(projection);
            reconcile(model); pump();
        }
        public void selection(SyntaxSelection value) {
            edt(); if (disposed) return;
            value.language().ifPresent(catalog::language);
            if (!selection.equals(value)) { selection = value; reconcile(model); pump(); }
        }
        public void enabled(boolean value) {
            edt(); if (!disposed && enabled != value) { enabled = value; reconcile(model); pump(); }
        }
        public void activity(boolean showing, boolean hasFocus) {
            edt(); if (disposed || visible == showing && focused == hasFocus) return;
            boolean priority = showing && (!visible || hasFocus && !focused);
            visible = showing; focused = hasFocus;
            if (priority && entry != null && entry.blocked) { entry.blocked = false; entry.ready = true; entry.ticket = ++sequence; }
            reconcile(model); pump();
        }
        public void retry() {
            edt(); if (disposed) return;
            reconcile(model);
            if (entry != null) { invalidate(entry); schedule(entry); }
            pump();
        }
        public void close() {
            edt(); if (disposed) return;
            disposed = true; model.owners.remove(this); handles--; entry = null;
            reconcile(model); pump();
        }
    }

    private void reconcile(Model model) {
        boolean attach = !closed && model.owners.stream().anyMatch(owner -> owner.enabled && owner.source.complete());
        if (attach != model.attached) {
            if (attach) model.document.addDocumentListener(model.listener); else model.document.removeDocumentListener(model.listener);
            model.attached = attach;
        }
        String prefix = "";
        if (attach) try { prefix = model.document.getText(0, Math.min(256, model.document.getLength())); }
        catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
        boolean nativeLayout = Boolean.TRUE.equals(model.document.getProperty("i18n"));
        for (Handle owner : model.owners) {
            owner.language = resolver.resolve(owner.source, owner.selection, prefix);
            owner.entry = owner.enabled && !nativeLayout && !owner.language.equals(SyntaxLanguageId.PLAIN)
                    ? model.entries.get(owner.language) : null;
        }
        cleanup(model);
        for (Handle owner : model.owners) if (owner.entry == null && owner.enabled && owner.source.complete() && !nativeLayout
                && !owner.language.equals(SyntaxLanguageId.PLAIN)) {
            owner.entry = model.entries.get(owner.language);
            if (owner.entry == null && projections < limits.entries()) { owner.entry = new Projection(model, owner.language); model.entries.put(owner.language, owner.entry); projections++; }
        }
        for (Projection entry : model.entries.values()) {
            if (!entry.visible()) invalidate(entry);
            else if (entry.result == null && entry.cancelTimer == null && !entry.ready && !entry.blocked) schedule(entry);
        }
        for (Handle owner : List.copyOf(model.owners)) owner.changed.run();
        cleanup(model);
    }
    private void cleanup(Model model) {
        var iterator = model.entries.values().iterator();
        while (iterator.hasNext()) {
            Projection entry = iterator.next();
            if (!entry.owned()) {
                invalidate(entry);
                if (entry.job == null) { iterator.remove(); projections--; }
            }
        }
        if (model.owners.isEmpty() && model.entries.isEmpty()) models.remove(model.document);
    }
    private void dropResult(Projection entry) { if (entry.result != null) { payload -= entry.result.payloadBytes(); entry.result = null; } }
    private void invalidate(Projection entry) {
        entry.request++; entry.ready = false; entry.blocked = false;
        if (entry.cancelTimer != null) { entry.cancelTimer.run(); entry.cancelTimer = null; }
        if (entry.job != null) entry.job.cancelled().set(true);
        dropResult(entry); entry.status = SyntaxStatus.of(PENDING, "Waiting for syntax highlighting");
    }
    private void schedule(Projection entry) {
        if (closed || !entry.visible() || entry.cancelTimer != null || entry.ready || entry.blocked
                || entry.job != null && !entry.job.cancelled().get()) return;
        long request = entry.request;
        entry.cancelTimer = debounce.schedule((int) limits.debounce().toMillis(), () -> {
            entry.cancelTimer = null;
            if (closed || request != entry.request || !entry.visible() || entry.result != null) return;
            entry.ready = true; entry.ticket = ++sequence; pump();
        });
    }
    private List<Projection> entries() { return models.values().stream().flatMap(model -> model.entries.values().stream()).toList(); }
    private void pump() {
        if (closed || pumping) return;
        pumping = true;
        try {
            for (Model model : List.copyOf(models.values())) if (model.owners.stream().anyMatch(handle -> handle.enabled
                    && handle.entry == null && !handle.language.equals(SyntaxLanguageId.PLAIN))) reconcile(model);
            // A bounded registry plus coalesced flags is the backlog. Only 32 candidate keys are admitted at a time; no queued text copies.
            var candidates = entries().stream().filter(entry -> entry.ready && entry.visible() && entry.job == null)
                    .sorted(Comparator.<Projection, Boolean>comparing(Projection::focused).reversed().thenComparingLong(entry -> entry.ticket)).limit(32).toList();
            for (Projection entry : candidates) {
                if (running.size() >= limits.workers()) break;
                Document document = entry.model.document;
                if (document.getLength() > limits.characters() || document.getDefaultRootElement().getElementCount() > limits.lines()) {
                    entry.ready = false; entry.blocked = true; entry.status = SyntaxStatus.of(LIMIT_EXCEEDED, "Syntax text size or line count limit"); notifyOwners(entry); continue;
                }
                long reserved = limits.jobBytes(document.getLength());
                if (payload + reserved > limits.payloadBytes() && entry.focused()) {
                    for (Projection other : entries()) if (other != entry && !other.focused() && other.result != null) {
                        dropResult(other); other.blocked = true; other.ready = false;
                        other.status = SyntaxStatus.of(CAPACITY_DEFERRED, "Syntax cache budget · focus or retry to highlight"); notifyOwners(other);
                        if (payload + reserved <= limits.payloadBytes()) break;
                    }
                }
                if (payload + reserved > limits.payloadBytes()) { entry.status = SyntaxStatus.of(CAPACITY_DEFERRED, "Syntax memory budget · waiting for capacity"); notifyOwners(entry); continue; }
                entry.ready = false; payload += reserved;
                Running job = new Running(++sequence, entry, entry.model.generation, entry.request, reserved, new AtomicBoolean());
                entry.job = job; running.put(job.id(), job);
                try {
                    long captureStarted = clock.getAsLong();
                    SyntaxInput input = new SyntaxInput(entry.language, document.getText(0, document.getLength()));
                    lastCaptureNanos = clock.getAsLong() - captureStarted;
                    var reference = new WeakReference<>(this);
                    SyntaxLexerProvider provider = catalog.provider(entry.language);
                    AtomicBoolean cancelled = job.cancelled(); long id = job.id(); SyntaxLimits policy = limits; LongSupplier time = clock;
                    workers.execute(() -> calculate(reference, id, provider, input, policy, cancelled, time));
                } catch (BadLocationException | RejectedExecutionException failure) {
                    complete(job.id(), null, SyntaxStatus.of(LEXER_FAILED, "Syntax worker unavailable"), 0);
                }
            }
        } finally { pumping = false; }
    }
    private static void calculate(WeakReference<SyntaxProjectionRegistry> owner, long id, SyntaxLexerProvider provider,
                                  SyntaxInput input, SyntaxLimits limits, AtomicBoolean cancelled, LongSupplier clock) {
        SyntaxResult result = null; SyntaxStatus failure = null;
        long started = clock.getAsLong();
        try {
            var budget = new SyntaxWorkBudget(limits, cancelled::get, clock); budget.checkInput(input.text());
            try (SyntaxLexer lexer = provider.create(input.language())) { result = lexer.lex(input, budget); }
            budget.check();
            if (result == null || !result.language().equals(input.language()) || result.length() != input.text().length()
                    || result.size() > limits.spans()) throw SyntaxFailure.invalid();
        } catch (SyntaxFailure problem) {
            result = null;
            failure = SyntaxStatus.of(problem.reason() == SyntaxFailure.Reason.LIMIT ? LIMIT_EXCEEDED : LEXER_FAILED,
                    problem.reason() == SyntaxFailure.Reason.LIMIT ? problem.getMessage() : "Syntax calculation cancelled or invalid");
        } catch (RuntimeException problem) { result = null; failure = SyntaxStatus.of(LEXER_FAILED, "Syntax provider failed · retry or choose plain text"); }
        finally {
            SyntaxResult tokens = result; SyntaxStatus problem = failure;
            long elapsed = clock.getAsLong() - started;
            SwingUtilities.invokeLater(() -> { var registry = owner.get(); if (registry != null) registry.complete(id, tokens, problem, elapsed); });
        }
    }
    private void complete(long id, SyntaxResult result, SyntaxStatus failure, long elapsed) {
        edt(); Running job = running.remove(id); if (job == null) return;
        long started = clock.getAsLong(); lastLexNanos = elapsed;
        payload -= job.reserved(); completed++;
        Projection entry = job.entry(); entry.job = null;
        if (!closed && !job.cancelled().get() && entry.request == job.request() && entry.model.generation == job.generation() && entry.visible()) {
            if (failure == null && result != null) { entry.result = result; payload += result.payloadBytes(); entry.status = SyntaxStatus.of(READY, "Syntax highlighting ready"); }
            else { entry.blocked = true; entry.status = failure == null ? SyntaxStatus.of(LEXER_FAILED, "Syntax provider failed") : failure; }
            notifyOwners(entry);
        } else discarded++;
        lastPublicationNanos = clock.getAsLong() - started;
        cleanup(entry.model);
        // Revisit handles which could not acquire a projection while the bounded registry was full.
        for (Model model : List.copyOf(models.values())) if (model.owners.stream().anyMatch(handle -> handle.entry == null && !handle.language.equals(SyntaxLanguageId.PLAIN))) reconcile(model);
        pump();
    }
    private void notifyOwners(Projection entry) { for (Handle owner : List.copyOf(entry.model.owners)) if (owner.entry == entry) owner.changed.run(); }
    @Override public void close() {
        edt(); if (closed) return; closed = true;
        for (Model model : List.copyOf(models.values())) {
            for (var entry : model.entries.values()) invalidate(entry);
            if (model.attached) model.document.removeDocumentListener(model.listener);
            model.attached = false;
            for (Handle owner : model.owners) { owner.disposed = true; owner.entry = null; }
            model.owners.clear(); cleanup(model);
        }
        handles = 0;
    }
    private static void edt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Syntax presentation requires the EDT"); }
}
