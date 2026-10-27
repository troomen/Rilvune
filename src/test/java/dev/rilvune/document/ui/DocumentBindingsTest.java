package dev.rilvune.document.ui;

import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.settings.domain.EditorSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DocumentBindingsTest {
    @TempDir Path directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<EditorView> views = new ArrayList<>();
    private DocumentService documents;
    private DocumentBindings bindings;
    private DocumentSession session;
    private Path file;

    @BeforeEach void prepare() throws Exception {
        documents = new DocumentService(new NioDocumentStore(10000), new FileDraftRepository(directory.resolve("drafts")),
                io, scheduler, SwingUtilities::invokeLater, Duration.ofDays(1));
        bindings = new DocumentBindings(documents);
        file = Files.writeString(directory.resolve("file.txt"), "first\nsecond\n");
        session = documents.open(file).get(5, TimeUnit.SECONDS);
    }

    @AfterEach void close() throws Exception {
        edt(() -> { views.forEach(EditorView::close); return null; });
        documents.close().get(5, TimeUnit.SECONDS);
        edt(() -> { bindings.close(); return null; });
        scheduler.shutdownNow(); io.close();
    }

    @Test void sharedViewsHaveOneModelOneUndoHistoryAndOnePersistenceStream() throws Exception {
        DocumentBindings.Lease first = edt(() -> bindings.acquire(session));
        DocumentBindings.Lease second = edt(() -> bindings.acquire(session));
        EditorView left = edt(() -> view(first));
        EditorView right = edt(() -> view(second));
        edt(() -> {
            assertSame(left.textComponent().getDocument(), right.textComponent().getDocument());
            assertSame(left.undoHistory(), right.undoHistory());
            left.textComponent().setCaretPosition(2); right.textComponent().setCaretPosition(8);
            long revision = session.revision();
            left.textComponent().append("more\n");
            assertEquals(revision + 1, session.revision(), "Exactly one bridge must forward the edit");
            assertEquals(left.textComponent().getText(), right.textComponent().getText());
            assertEquals(2, left.textComponent().getCaretPosition());
            assertEquals(8, right.textComponent().getCaretPosition());
            return null;
        });
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("first\nsecond\nmore\n", Files.readString(file));
        edt(() -> { right.textEditor().undo(); return null; });
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("first\nsecond\n", Files.readString(file));
        edt(() -> { right.textEditor().redo(); return null; });
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("first\nsecond\nmore\n", Files.readString(file));

        edt(first::release).get(5, TimeUnit.SECONDS);
        edt(() -> { left.close(); right.textComponent().append("after close\n"); return null; });
        assertFalse(session.isClosed());
        edt(second::release).get(5, TimeUnit.SECONDS);
        assertTrue(session.isClosed());
        assertEquals("first\nsecond\nmore\nafter close\n", Files.readString(file));
    }

    @Test void suspensionGuardsTheModelAndUndoAcrossEveryView() throws Exception {
        EditorView left = edt(() -> view(bindings.acquire(session)));
        EditorView right = edt(() -> view(bindings.acquire(session)));
        edt(() -> {
            left.textComponent().append("edit\n");
            left.setEditingEnabled(false);
            assertFalse(left.textComponent().isEditable());
            assertFalse(right.textComponent().isEditable());
            right.textComponent().setEditable(true); // A view flag alone cannot grant model mutation.
            right.textComponent().append("blocked");
            right.undoHistory().undo();
            right.textEditor().newLine();
            assertEquals("first\nsecond\nedit\n", session.text());
            left.setEditingEnabled(true);
            right.textEditor().undo();
            assertEquals("first\nsecond\n", session.text());
            return null;
        });
    }

    @Test void modelReconciliationPreservesEachViewsSelectionAndDoesNotEchoAnEdit() throws Exception {
        EditorView left = edt(() -> view(bindings.acquire(session)));
        EditorView right = edt(() -> view(bindings.acquire(session)));
        edt(() -> { left.textComponent().select(0, 2); right.textComponent().select(6, 8); return null; });
        session.replaceText("first\nexternal\n");
        long revision = session.revision();
        session.flush().get(5, TimeUnit.SECONDS);
        edt(() -> {
            assertEquals("first\nexternal\n", left.textComponent().getText());
            assertEquals(left.textComponent().getText(), right.textComponent().getText());
            assertEquals(0, left.textComponent().getSelectionStart()); assertEquals(2, left.textComponent().getSelectionEnd());
            assertEquals(6, right.textComponent().getSelectionStart()); assertEquals(8, right.textComponent().getSelectionEnd());
            assertEquals(revision, session.revision(), "Model reconciliation must not feed back into the session");
            right.textEditor().undo();
            assertEquals("first\nsecond\n", session.text());
            return null;
        });
    }

    private EditorView view(DocumentBindings.Lease lease) {
        EditorView view = new EditorView(lease.binding(), EditorSettings.defaults(), EditorTheme.graphite(),
                ignored -> { }, ignored -> { }, message -> fail(message), ignored -> { });
        views.add(view);
        return view;
    }

    @Test void disposingViewsDetachesSwingListenersFromTheStillLiveModel() throws Exception {
        DocumentBindings.Lease first = edt(() -> bindings.acquire(session));
        DocumentBindings.Lease second = edt(() -> bindings.acquire(session));
        edt(() -> {
            var model = (javax.swing.text.AbstractDocument) first.binding().model();
            int baseline = model.getDocumentListeners().length;
            EditorView left = view(first); EditorView right = view(second);
            assertTrue(model.getDocumentListeners().length > baseline);
            left.close(); right.close();
            assertEquals(baseline, model.getDocumentListeners().length);
            assertFalse(session.isClosed());
            return null;
        });
    }

    @Test void closingANormalTabKeepsAnotherConsumerEditableWithItsHistory() throws Exception {
        TextTabs tabs = edt(() -> new TextTabs(bindings, EditorTheme.graphite(), EditorSettings.defaults(),
                message -> { }, ignored -> { }, ignored -> { }, () -> { }, () -> { }, ignored -> { throw new AssertionError("Unexpected menu request"); }, ignored -> { }));
        EditorView normal = edt(() -> tabs.show(session));
        DocumentBindings.Lease another = edt(() -> bindings.acquire(session));
        EditorView remaining = edt(() -> view(another));
        edt(() -> { normal.textComponent().append("from tab\n"); return null; });
        assertTrue(edt(() -> tabs.requestClose(normal)).get(5, TimeUnit.SECONDS));
        edt(() -> {
            assertTrue(tabs.views().isEmpty()); assertFalse(session.isClosed());
            assertTrue(remaining.textComponent().isEditable());
            remaining.textEditor().undo();
            assertEquals("first\nsecond\n", session.text());
            return null;
        });
        edt(another::release).get(5, TimeUnit.SECONDS);
        assertTrue(session.isClosed());
    }

    @Test void restoredIdentityGetsAFreshBindingAndOldReleaseCannotRemoveIt() throws Exception {
        DocumentSession draft = documents.createDraft();
        draft.replaceText("recovered content");
        DocumentBindings.Lease old = edt(() -> bindings.acquire(draft));
        EditorView previous = edt(() -> view(old));
        draft.close().get(5, TimeUnit.SECONDS);
        DocumentSession restored = documents.restoreDrafts().get(5, TimeUnit.SECONDS).stream()
                .filter(candidate -> candidate.id().equals(draft.id())).findFirst().orElseThrow();
        DocumentBindings.Lease current = edt(() -> bindings.acquire(restored));
        EditorView reopened = edt(() -> view(current));
        edt(() -> {
            assertNotSame(old.binding(), current.binding());
            assertFalse(previous.textComponent().isEditable());
            assertTrue(reopened.textComponent().isEditable());
            return null;
        });
        edt(old::release).get(5, TimeUnit.SECONDS);
        DocumentBindings.Lease additional = edt(() -> bindings.acquire(restored));
        assertSame(current.binding(), additional.binding());
        edt(() -> { reopened.textComponent().append(" still live"); return null; });
        edt(additional::release).get(5, TimeUnit.SECONDS);
        edt(current::release).get(5, TimeUnit.SECONDS);
        assertTrue(restored.isClosed());
    }
    @Test void projectReplacementSharesViewsAndUndoWithoutOwningTheirLifetime() throws Exception {
        DocumentBindings.Lease first = edt(() -> bindings.acquire(session));
        DocumentBindings.Lease second = edt(() -> bindings.acquire(session));
        EditorView left = edt(() -> view(first));
        EditorView right = edt(() -> view(second));
        var replacement = new dev.rilvune.search.application.ProjectReplacement(documents, SwingUtilities::invokeLater);
        var plan = replacement.prepare(List.of(new dev.rilvune.search.application.ProjectSearch.Hit(file, 1, "first", 0)),
                "first", "FIRST", true, false).get(5, TimeUnit.SECONDS);
        var result = replacement.apply(plan).get(5, TimeUnit.SECONDS);
        assertTrue(result.successful(), result.details());
        edt(() -> {
            assertFalse(session.isClosed());
            assertEquals("FIRST\nsecond\n", left.textComponent().getText());
            assertEquals(left.textComponent().getText(), right.textComponent().getText());
            right.textEditor().undo();
            assertEquals("first\nsecond\n", left.textComponent().getText());
            return null;
        });
        session.flush().get(5, TimeUnit.SECONDS);
        edt(first::release).get(5, TimeUnit.SECONDS);
        assertFalse(session.isClosed());
        edt(second::release).get(5, TimeUnit.SECONDS);
        assertTrue(session.isClosed());
        assertEquals("first\nsecond\n", Files.readString(file));
    }

    @Test void bulkReplacementAndUndoPublishOnlyOneFinalRevisionAcrossViews() throws Exception {
        DocumentBindings.Lease lease = edt(() -> bindings.acquire(session));
        EditorView left = edt(() -> view(lease));
        EditorView right = edt(() -> view(bindings.acquire(session)));
        edt(() -> {
            var commands = lease.binding().commands();
            long initial = session.revision();
            String before = session.text(), after = "changed\n".repeat(1000);
            assertTrue(commands.replaceText(left.textComponent().getDocument(), before, after));
            assertEquals(initial + 1, session.revision());
            assertEquals(after, right.textComponent().getText());
            right.textEditor().undo();
            assertEquals(before, session.text()); assertEquals(initial + 2, session.revision());
            left.textEditor().redo();
            assertEquals(after, session.text()); assertEquals(initial + 3, session.revision());
            assertFalse(commands.replaceText(left.textComponent().getDocument(), before, "stale"));
            left.setEditingEnabled(false);
            assertFalse(commands.replaceText(left.textComponent().getDocument(), after, "blocked"));
            left.setEditingEnabled(true);
            return null;
        });
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("changed\n".repeat(1000), Files.readString(file));
    }

    @Test void programmaticCommitReconcilesViewsBeforeTheNextKeystrokeAndKeepsSharedUndo() throws Exception {
        DocumentBindings.Lease lease = edt(() -> bindings.acquire(session));
        EditorView left = edt(() -> view(lease)); EditorView right = edt(() -> view(bindings.acquire(session)));
        var typeImmediately = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.Executor editing = command -> SwingUtilities.invokeLater(() -> {
            command.run();
            if (typeImmediately.get() && session.text().equals("programmatic")) {
                typeImmediately.set(false);
                left.textComponent().append(" user");
            }
        });
        var gateway = new dev.rilvune.workspace.application.ProjectDocuments(documents,
                new dev.rilvune.workspace.infrastructure.NioWorkspaceFiles(10000), editing, io);
        var context = dev.rilvune.workspace.domain.ProjectContext.activate(new dev.rilvune.workspace.domain.Workspace(directory));
        gateway.resume(context);
        var consumer = gateway.open(context, Path.of("file.txt")).get(5, TimeUnit.SECONDS);
        var version = consumer.snapshot().version(); typeImmediately.set(true);
        try {
            var result = consumer.apply(version, "programmatic").get(5, TimeUnit.SECONDS); assertTrue(result.accepted());
            edt(() -> {
                assertFalse(typeImmediately.get());
                assertEquals("programmatic user", session.text());
                assertEquals(session.text(), left.textComponent().getText());
                assertEquals(session.text(), right.textComponent().getText());
                right.textEditor().undo(); assertEquals("programmatic", session.text());
                right.textEditor().undo(); assertEquals("first\nsecond\n", session.text());
                left.textEditor().redo(); assertEquals("programmatic", session.text());
                return null;
            });
        } finally { gateway.quiesce().get(5, TimeUnit.SECONDS); }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}
