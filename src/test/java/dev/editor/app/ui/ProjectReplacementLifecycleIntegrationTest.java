package dev.editor.app.ui;

import dev.editor.diff.infrastructure.DiffLimits;
import dev.editor.diff.infrastructure.MyersDiffEngine;
import dev.editor.document.application.DocumentService;
import dev.editor.document.domain.DraftRepository;
import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import dev.editor.document.ui.DocumentBindings;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.FontCatalog;
import dev.editor.search.ui.ProjectSearchDialog;
import dev.editor.session.infrastructure.SessionRepository;
import dev.editor.settings.application.SettingsService;
import dev.editor.settings.infrastructure.PropertiesSettingsStore;
import dev.editor.workspace.infrastructure.NioWorkspaceFiles;
import dev.editor.workspace.infrastructure.WorkspaceOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(30)
class ProjectReplacementLifecycleIntegrationTest {
    @TempDir Path directory;
    private final AtomicBoolean failRelease = new AtomicBoolean();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private MainWindow window;
    private DocumentService documents;

    @AfterEach void close() throws Exception {
        failRelease.set(false);
        edt(() -> { for (Window owned : Window.getWindows()) if (owned instanceof JDialog) owned.dispose(); return null; });
        if (window != null && edt(window::isDisplayable)) edt(window::finishSnapshot).get(8, TimeUnit.SECONDS);
        scheduler.shutdownNow(); io.close();
    }

    @Test void failedCleanupRemainsRetryableAfterDialogDisposalAndKeepsLaterTabUsable() throws Exception {
        Path file = Files.writeString(directory.resolve("file.txt"), "needle\n");
        createWindow();
        ProjectSearchDialog dialog = prepareConfirmation();
        failRelease.set(true);
        answer("Replace in files", JOptionPane.OK_OPTION);
        showing(JButton.class, button -> "replacementRetryCleanup".equals(button.getName()));
        assertEquals("updated\n", Files.readString(file));
        edt(() -> { dialog.dispose(); return null; });
        JButton retry = showing(JButton.class, button -> "retryReplacementCleanup".equals(button.getName()));
        assertEquals(1, documents.sessions().size());
        edt(() -> window.openFile(file)).get(5, TimeUnit.SECONDS);
        var view = edt(() -> window.editorTabs().active().orElseThrow());
        edt(() -> { view.textComponent().append("a later edit\n"); retry.doClick(); return null; });
        await(() -> ui(() -> !retry.isVisible()));
        assertFalse(view.session().isClosed());
        assertEquals("updated\na later edit\n", view.session().text());
        view.session().flush().get(5, TimeUnit.SECONDS);
        assertEquals("updated\na later edit\n", Files.readString(file));
    }

    @Test void failedDrainPreventsProjectTransitionAndCanBeRetried() throws Exception {
        Path file = Files.writeString(directory.resolve("file.txt"), "needle\n");
        Path next = Files.createDirectory(directory.resolve("next"));
        createWindow();
        ProjectSearchDialog dialog = prepareConfirmation();
        failRelease.set(true);
        CompletableFuture<Void> switched = edt(() -> window.openProject(next));
        showing(JDialog.class, candidate -> "Open project".equals(candidate.getTitle()));
        assertEquals(directory, edt(() -> window.currentWorkspace().root()));
        assertFalse(edt(dialog::isDisplayable));
        answer("Open project", JOptionPane.OK_OPTION);
        assertThrows(ExecutionException.class, () -> switched.get(5, TimeUnit.SECONDS));
        assertEquals("needle\n", Files.readString(file));
        failRelease.set(false);
        JButton retry = showing(JButton.class, button -> "retryReplacementCleanup".equals(button.getName()));
        edt(() -> { retry.doClick(); return null; });
        await(() -> ui(() -> !retry.isVisible()));
        assertTrue(documents.sessions().isEmpty());
        edt(() -> window.openProject(next)).get(5, TimeUnit.SECONDS);
        assertEquals(next, edt(() -> window.currentWorkspace().root()));
    }

    @Test void failedScopedConsumerCleanupBlocksProjectActivationUntilRetry() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "base"); Path next = Files.createDirectory(directory.resolve("next"));
        createWindow(); var context = edt(window::projectContext);
        var consumer = window.projectDocuments().open(context, Path.of("file.txt")).get(5, TimeUnit.SECONDS);
        failRelease.set(true);
        var switched = edt(() -> window.openProject(next));
        showing(JDialog.class, candidate -> "Open project".equals(candidate.getTitle()));
        assertEquals(directory, edt(() -> window.currentWorkspace().root())); assertNull(edt(window::projectContext));
        answer("Open project", JOptionPane.OK_OPTION);
        assertThrows(ExecutionException.class, () -> switched.get(5, TimeUnit.SECONDS));
        assertFalse(documents.sessions().isEmpty());
        failRelease.set(false); edt(() -> window.openProject(next)).get(5, TimeUnit.SECONDS);
        assertEquals(next, edt(() -> window.currentWorkspace().root())); assertTrue(documents.sessions().isEmpty());
        assertEquals(dev.editor.workspace.application.ProjectDocuments.Rejection.STALE_PROJECT,
                consumer.apply(null, "late").get(5, TimeUnit.SECONDS).rejection());
    }

    private void createWindow() throws Exception {
        Path profile = Files.createDirectories(directory.resolve("profile"));
        var storage = new FileDraftRepository(profile.resolve("drafts"));
        DraftRepository drafts = new DraftRepository() {
            @Override public boolean claim(UUID id) throws IOException { return storage.claim(id); }
            @Override public void save(RecoveryDraft draft) throws IOException { storage.save(draft); }
            @Override public List<RecoveryDraft> load() throws IOException { return storage.load(); }
            @Override public void remove(UUID id) throws IOException { storage.remove(id); }
            @Override public void release(UUID id) throws IOException {
                if (failRelease.get()) throw new IOException("Recovery release unavailable");
                storage.release(id);
            }
        };
        FontCatalog.registerBundled();
        var settings = new SettingsService(new PropertiesSettingsStore(profile), io, SwingUtilities::invokeLater);
        var files = new NioWorkspaceFiles(10000);
        documents = new DocumentService(new NioDocumentStore(10000), drafts, io, scheduler, SwingUtilities::invokeLater, Duration.ofDays(1));
        window = edt(() -> {
            var theme = EditorTheme.from(settings.current()); theme.install();
            var result = new MainWindow(theme, files.openWorkspace(directory), files, documents, new DocumentBindings(documents),
                    new MyersDiffEngine(DiffLimits.interactive()), settings, new SessionRepository(profile),
                    new WorkspaceOperations(directory, profile), directory, new FontCatalog(), io, () -> { });
            result.setVisible(true); return result;
        });
        edt(window::loadProject).get(5, TimeUnit.SECONDS);
    }

    private ProjectSearchDialog prepareConfirmation() throws Exception {
        SwingUtilities.invokeLater(() -> window.getRootPane().getActionMap().get("navigate.replace")
                .actionPerformed(new ActionEvent(window, 0, "navigate.replace")));
        JTextField query = showing(JTextField.class, field -> "projectSearchQuery".equals(field.getName()));
        edt(() -> { query.setText("needle"); return null; });
        JList<?> results = showing(JList.class, list -> "projectSearchResults".equals(list.getName()));
        await(() -> ui(() -> results.getModel().getSize() == 1));
        JTextField replacement = showing(JTextField.class, field -> "projectReplacement".equals(field.getName()));
        edt(() -> { replacement.setText("updated"); return null; });
        JButton all = showing(JButton.class, button -> "Replace all…".equals(button.getText()));
        SwingUtilities.invokeLater(all::doClick);
        showing(JDialog.class, dialog -> "Replace in files".equals(dialog.getTitle()));
        return (ProjectSearchDialog) edt(() -> SwingUtilities.getWindowAncestor(query));
    }

    private void answer(String title, int answer) throws Exception {
        JDialog dialog = showing(JDialog.class, candidate -> title.equals(candidate.getTitle()));
        edt(() -> { components(dialog, JOptionPane.class).getFirst().setValue(answer); return null; });
    }
    private static <T extends Component> T showing(Class<T> type, Predicate<T> predicate) throws Exception {
        var found = new java.util.concurrent.atomic.AtomicReference<T>();
        await(() -> ui(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                for (T component : components(window, type)) if (component.isShowing() && predicate.test(component)) { found.set(component); return true; }
            }
            return false;
        }));
        return found.get();
    }
    private static <T extends Component> List<T> components(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container container) for (Component child : container.getComponents()) result.addAll(components(child, type));
        return result;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for replacement lifecycle");
            Thread.sleep(10);
        }
    }
    private static boolean ui(Callable<Boolean> action) { try { return edt(action); } catch (Exception failure) { throw new RuntimeException(failure); } }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}
