package dev.rilvune.search.ui;

import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.search.application.ProjectSearch;
import dev.rilvune.workspace.infrastructure.NioWorkspaceFiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(20)
class ProjectSearchPreviewTest {
    @TempDir Path directory;
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private ProjectSearchDialog dialog;
    private JFrame owner;

    @AfterEach void close() throws Exception { edt(() -> { if (dialog != null) dialog.dispose(); if (owner != null) owner.dispose(); return null; }); }
    private Runnable take() throws Exception { Runnable task = work.poll(5, TimeUnit.SECONDS); assertNotNull(task); return task; }
    private void runNext() throws Exception { take().run(); edt(() -> null); }

    @Test void replacementPreviewCoalescesInputAndDiscardsCompletedStaleAndClosedResults() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "aaa end");
        edt(() -> {
            var files = new NioWorkspaceFiles(10000);
            owner = new JFrame(); owner.setSize(1000, 700);
            dialog = new ProjectSearchDialog(owner, EditorTheme.graphite(), files.openWorkspace(directory),
                    new ProjectSearch(files, 1000, Set.of()), null, work::add, false, true, (path, line) -> { }, path -> null);
            component(dialog, "projectSearchQuery", JTextField.class).setText("a"); return null;
        });
        runNext(); // Project search.
        runNext(); // Read the selected preview.
        runNext(); // Calculate replacement preview.
        JTextArea after = edt(() -> component(dialog, "projectReplacementPreview", JTextArea.class));
        JTextField replacement = edt(() -> component(dialog, "projectReplacement", JTextField.class));
        assertEquals(" end", edt(after::getText));
        edt(() -> { replacement.setText("x"); return null; });
        Runnable old = take();
        edt(() -> {
            old.run(); // Finish calculation, but hold its queued presentation until the query changes.
            replacement.setText("yy"); replacement.setText("zz"); return null;
        });
        runNext();
        assertEquals("zzzzzz end", edt(after::getText)); assertTrue(work.isEmpty());
        edt(() -> { replacement.setText("closed"); return null; });
        Runnable closing = take();
        edt(() -> { dialog.dispose(); return null; }); closing.run(); edt(() -> null);
        assertEquals("zzzzzz end", edt(after::getText)); assertTrue(work.isEmpty());
    }

    private static <T extends Component> T component(Container parent, String name, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container container) {
                T found = component(container, name, type); if (found != null) return found;
            }
        }
        return null;
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}
