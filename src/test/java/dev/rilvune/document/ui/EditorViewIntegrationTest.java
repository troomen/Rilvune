package dev.rilvune.document.ui;

import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.settings.application.SettingsService;
import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.domain.SyncState;
import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.document.infrastructure.FileDraftRepository;
import dev.rilvune.document.infrastructure.NioDocumentStore;
import dev.rilvune.settings.infrastructure.PropertiesSettingsStore;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises real Swing/document/persistence integration under the Docker Xvfb display. */
@Tag("ui")
@EnabledIfSystemProperty(named = "java.awt.headless", matches = "false")
@Timeout(25)
class EditorViewIntegrationTest {
    @TempDir Path directory;
    private ExecutorService io;
    private ScheduledExecutorService scheduler;
    private DocumentService documents;
    private DocumentSession session;
    private SettingsService settings;
    private EditorView view;
    private DocumentBindings bindings;
    private JFrame frame;
    private CountDownLatch ioRelease;
    private AutoCloseable settingsListener;
    private AWTEventListener windowListener;
    private final List<String> errors = new ArrayList<>();

    @BeforeAll
    static void fonts() throws Exception {
        FontCatalog.registerBundled();
        onEdt(() -> { EditorTheme.graphite().install(); return null; });
    }

    @BeforeEach
    void services() throws Exception {
        io = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        documents = new DocumentService(new NioDocumentStore(8 * 1024 * 1024, directory.resolve("locks")),
                new FileDraftRepository(directory.resolve("drafts")), io, scheduler,
                SwingUtilities::invokeLater, Duration.ofMillis(400));
        settings = new SettingsService(new PropertiesSettingsStore(directory.resolve("profile")), io, SwingUtilities::invokeLater);
    }

    @AfterEach
    void close() throws Exception {
        if (ioRelease != null) ioRelease.countDown();
        if (windowListener != null) Toolkit.getDefaultToolkit().removeAWTEventListener(windowListener);
        onEdt(() -> {
            if (view != null) view.close();
            if (frame != null) frame.dispose();
            for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose();
            return null;
        });
        if (settingsListener != null) settingsListener.close();
        if (documents != null) documents.close().get(8, TimeUnit.SECONDS);
        if (bindings != null) onEdt(() -> { bindings.close(); return null; });
        if (scheduler != null) scheduler.shutdownNow();
        if (io != null) { io.shutdown(); assertTrue(io.awaitTermination(8, TimeUnit.SECONDS)); }
    }

    @Test
    void recoveryFailureIsVisibleWhileSourceAutosaveAndEditingRemainUsable() throws Exception {
        documents.close().get(5, TimeUnit.SECONDS);
        var failRecovery = new java.util.concurrent.atomic.AtomicBoolean(true);
        var storage = new FileDraftRepository(directory.resolve("drafts"));
        dev.rilvune.document.domain.DraftRepository repository = new dev.rilvune.document.domain.DraftRepository() {
            @Override public boolean claim(java.util.UUID id) throws java.io.IOException { return storage.claim(id); }
            @Override public void release(java.util.UUID id) throws java.io.IOException { storage.release(id); }
            @Override public void save(dev.rilvune.document.domain.RecoveryDraft draft) throws java.io.IOException {
                if (failRecovery.get()) throw new java.io.IOException("Recovery disk is full");
                storage.save(draft);
            }
            @Override public List<dev.rilvune.document.domain.RecoveryDraft> load() throws java.io.IOException { return storage.load(); }
            @Override public void remove(java.util.UUID id) throws java.io.IOException {
                if (failRecovery.get()) throw new java.io.IOException("Recovery disk is full");
                storage.remove(id);
            }
        };
        documents = new DocumentService(new NioDocumentStore(10000), repository, io, scheduler,
                SwingUtilities::invokeLater, Duration.ofDays(1));
        try {
            Path file = show("initial\n");
            onEdt(() -> { view.textComponent().append("latest\n"); return null; });
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> session.flush().get(5, TimeUnit.SECONDS));
            onEdt(() -> {
                assertEquals("initial\nlatest\n", view.textComponent().getText());
                assertTrue(view.textComponent().isEditable());
                assertTrue(components(view, JLabel.class).stream().anyMatch(label -> label.getText() != null
                        && label.getText().toLowerCase(java.util.Locale.ROOT).contains("recovery write failed")));
                assertTrue(session.snapshot().details().contains("Recovery disk is full"));
                return null;
            });
            assertEquals("initial\nlatest\n", Files.readString(file));
            failRecovery.set(false);
            session.flush().get(5, TimeUnit.SECONDS);
            onEdt(() -> { assertFalse(session.snapshot().recoveryFailed()); return null; });
        } finally { failRecovery.set(false); }
    }

    @Test
    void typingSavesWithoutSaveCommandAndUndoAfterAutosavePersists() throws Exception {
        Path file = show("hello\n");

        onEdt(() -> { view.textComponent().append("Привет, мир!\n"); return null; });
        awaitSnapshot(value -> value.state() == SyncState.SYNCHRONIZED && value.text().equals("hello\nПривет, мир!\n"));

        assertEquals("hello\nПривет, мир!\n", Files.readString(file));
        assertTrue(onEdt(() -> view.undoHistory().canUndo()));
        onEdt(() -> { view.undoHistory().undo(); return null; });
        awaitSnapshot(value -> value.state() == SyncState.SYNCHRONIZED && value.text().equals("hello\n"));
        assertEquals("hello\n", Files.readString(file));
        assertEquals("hello\n", onEdt(() -> view.textComponent().getText()));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void externalChangesReachOpenEditorWithoutManualRefresh() throws Exception {
        Path file = show("first line\nsecond line\n");
        onEdt(() -> { view.textComponent().setCaretPosition(4); return null; });

        Files.writeString(file, "first line\nchanged outside\n");
        awaitSnapshot(value -> value.state() == SyncState.SYNCHRONIZED && value.text().contains("changed outside"));

        assertEquals("first line\nchanged outside\n", onEdt(() -> view.textComponent().getText()));
        assertEquals(4, onEdt(() -> view.textComponent().getCaretPosition()));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void persistedApplyUpdatesFontWithoutLosingTextSelectionViewportOrUndo() throws Exception {
        String initial = IntStream.range(1, 180).mapToObj(index -> "Line " + index + " — a long editable line with Cyrillic: Привет\n")
                .collect(Collectors.joining());
        Path file = show(initial);
        onEdt(() -> { view.textComponent().append("additional line\n"); return null; });
        awaitSnapshot(value -> value.state() == SyncState.SYNCHRONIZED && value.text().endsWith("additional line\n"));
        int selectionStart = onEdt(() -> view.textComponent().getLineStartOffset(24));
        Point viewport = onEdt(() -> {
            view.textComponent().select(selectionStart, selectionStart + 25);
            JScrollPane scroll = scrollPane();
            scroll.getViewport().setViewPosition(new Point(0, 360));
            return scroll.getViewport().getViewPosition();
        });
        EditorSettings larger = settings.current().withFonts("Hack", 18, "Hack", 25);

        CompletableFuture<EditorSettings> applied = onEdt(() -> settings.apply(larger));
        applied.get(8, TimeUnit.SECONDS);
        onEdt(() -> null); // Process the viewport restoration queued by applySettings.

        assertEquals(25, onEdt(() -> view.textComponent().getFont().getSize()));
        assertEquals("Hack", onEdt(() -> view.textComponent().getFont().getFamily()));
        assertEquals(initial + "additional line\n", onEdt(() -> view.textComponent().getText()));
        assertEquals(selectionStart, onEdt(() -> view.textComponent().getSelectionStart()));
        assertEquals(selectionStart + 25, onEdt(() -> view.textComponent().getSelectionEnd()));
        assertEquals(viewport, onEdt(() -> scrollPane().getViewport().getViewPosition()));
        assertEquals(larger, new PropertiesSettingsStore(directory.resolve("profile")).load().settings());
        assertTrue(onEdt(() -> view.undoHistory().canUndo()));
        onEdt(() -> { view.undoHistory().undo(); return null; });
        awaitSnapshot(value -> value.state() == SyncState.SYNCHRONIZED && value.text().equals(initial));
        assertEquals(initial, Files.readString(file));
    }

    @Test
    void conflictingFileShowsBothVersionsAndWaitsForConfirmedChoice() throws Exception {
        Path file = show("original\n");
        CountDownLatch entered = new CountDownLatch(1);
        ioRelease = new CountDownLatch(1);
        io.execute(() -> {
            entered.countDown();
            try { ioRelease.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        onEdt(() -> { view.textComponent().replaceRange("editor version", 0, 8); return null; });
        Files.writeString(file, "external version\n");
        ioRelease.countDown();
        awaitSnapshot(value -> value.state() == SyncState.CONFLICT);

        assertEquals("external version\n", Files.readString(file));
        assertEquals("editor version\n", onEdt(() -> view.textComponent().getText()));
        assertTrue(onEdt(() -> visibleButton("Use disk") != null));
        assertTrue(onEdt(() -> visibleButton("Keep editor") != null));
        onEdt(() -> { visibleButton("Compare").doClick(); return null; });
        onEdt(() -> {
            JDialog comparison = java.util.Arrays.stream(Window.getWindows())
                    .filter(window -> window.isShowing() && window instanceof JDialog dialog && dialog.getTitle().startsWith("Compare"))
                    .map(window -> (JDialog) window).findFirst().orElseThrow();
            List<JTextArea> texts = components(comparison, JTextArea.class);
            assertTrue(texts.stream().anyMatch(text -> text.getText().equals("editor version\n")));
            assertTrue(texts.stream().anyMatch(text -> text.getText().equals("external version\n")));
            comparison.dispose();
            visibleButton("Decide later").doClick();
            return null;
        });
        assertEquals(SyncState.CONFLICT, session.state());
        assertEquals("external version\n", Files.readString(file));

        CompletableFuture<Void> confirmation = new CompletableFuture<>();
        windowListener = event -> {
            if (!(event instanceof WindowEvent window) || window.getID() != WindowEvent.WINDOW_OPENED
                    || !(window.getWindow() instanceof JDialog dialog) || !dialog.getTitle().equals("Resolve document")) return;
            JOptionPane pane = components(dialog, JOptionPane.class).stream().findFirst().orElseThrow();
            confirmation.complete(null);
            SwingUtilities.invokeLater(() -> pane.setValue(JOptionPane.OK_OPTION));
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(windowListener, AWTEvent.WINDOW_EVENT_MASK);
        SwingUtilities.invokeLater(() -> visibleButton("Use disk").doClick());
        confirmation.get(8, TimeUnit.SECONDS);
        awaitSnapshot(value -> value.state() == SyncState.SYNCHRONIZED && value.text().equals("external version\n"));

        assertEquals("external version\n", onEdt(() -> view.textComponent().getText()));
        assertFalse(onEdt(() -> components(view, JLabel.class).stream()
                .anyMatch(label -> label.isShowing() && label.getText() != null && label.getText().contains("awaiting your decision"))));
        assertEquals("external version\n", Files.readString(file));
    }

    private Path show(String content) throws Exception {
        Path file = directory.resolve("document.txt");
        Files.writeString(file, content);
        session = documents.open(file).get(8, TimeUnit.SECONDS);
        onEdt(() -> {
            EditorTheme theme = EditorTheme.from(settings.current());
            theme.install();
            bindings = new DocumentBindings(documents);
            view = new EditorView(bindings.acquire(session).binding(), settings.current(), theme, snapshot -> { }, ignored -> { }, errors::add, snapshot -> {
                var dialog = new dev.rilvune.diff.ui.SnapshotDiffDialog(frame, "Compare · " + snapshot.name(),
                        new dev.rilvune.diff.ui.SnapshotDiffDialog.Source("Editor", "Preserved", new dev.rilvune.diff.domain.TextSnapshot("editor", snapshot.revision(), snapshot.text())),
                        new dev.rilvune.diff.ui.SnapshotDiffDialog.Source("Disk", "Observed", new dev.rilvune.diff.domain.TextSnapshot("disk", snapshot.revision(), snapshot.externalText())),
                        new dev.rilvune.platform.text.ui.TextEditorSettings(4, true, false, true, false, true), theme,
                        new dev.rilvune.diff.infrastructure.MyersDiffEngine(dev.rilvune.diff.infrastructure.DiffLimits.interactive()), io, errors::add);
                dialog.setVisible(true);
            });
            settingsListener = settings.listen(value -> {
                EditorTheme updated = EditorTheme.from(value);
                updated.install();
                view.applySettings(value, updated);
            });
            frame = new JFrame("Editor UI integration");
            frame.setContentPane(view);
            frame.setSize(1000, 650);
            frame.setLocation(20, 20);
            frame.setVisible(true);
            return null;
        });
        onEdt(() -> null);
        return file;
    }

    private void awaitSnapshot(Predicate<DocumentSnapshot> condition) throws Exception {
        CompletableFuture<DocumentSnapshot> received = new CompletableFuture<>();
        try (AutoCloseable listener = session.addListener(snapshot -> {
            if (condition.test(snapshot)) received.complete(snapshot);
        })) {
            DocumentSnapshot existing = session.snapshot();
            if (condition.test(existing)) received.complete(existing);
            received.get(8, TimeUnit.SECONDS);
        }
        onEdt(() -> null);
    }

    private JScrollPane scrollPane() {
        return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, view.textComponent());
    }

    private JButton visibleButton(String title) {
        return components(view, JButton.class).stream().filter(button -> button.isShowing() && title.equals(button.getText())).findFirst().orElse(null);
    }

    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) found.addAll(components(container, type));
        }
        return found;
    }

    private static <T> T onEdt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        FutureTask<T> task = new FutureTask<>(operation);
        SwingUtilities.invokeAndWait(task);
        return task.get(8, TimeUnit.SECONDS);
    }
}
