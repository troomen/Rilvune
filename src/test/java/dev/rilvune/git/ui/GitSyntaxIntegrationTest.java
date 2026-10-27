package dev.rilvune.git.ui;

import dev.rilvune.git.application.GitWorkspaceService;
import dev.rilvune.git.domain.*;
import dev.rilvune.diff.infrastructure.*;
import dev.rilvune.platform.text.domain.syntax.*;
import dev.rilvune.platform.text.infrastructure.syntax.rsta.RstaSyntaxProvider;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.text.ui.syntax.*;
import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui") @Timeout(20)
class GitSyntaxIntegrationTest {
    @Test void renamedSidesUseTheirOwnLanguagesAndClearingDropsAllSyntaxOwnership() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        var registry = new SyntaxProjectionRegistry(new SyntaxCatalog(List.of(new RstaSyntaxProvider().contribution())), SyntaxLimits.interactive(), workers);
        JFrame frame = edt(() -> new JFrame("Git syntax integration"));
        GitDiffPanel panel = null;
        try {
            panel = edt(() -> {
                var theme = EditorTheme.graphite(); theme.install();
                var value = new GitDiffPanel(new TextEditorSettings(4, true, false, true, false, false), theme,
                        new MyersDiffEngine(DiffLimits.interactive()), workers, Assertions::fail);
                value.syntaxSupport(registry); frame.add(value); frame.setSize(1000, 600); frame.setVisible(true);
                value.show(new GitWorkspaceService.Comparison(new GitContent("public class Before {}\n", "Before", "", true),
                                new GitContent("def after():\n    return 42\n", "After", "", true)), GitSource.working(), GitSource.working(),
                        new GitChange("src/after.py", "src/Before.java", "R", 'R', ' ', false), "", ignored -> { }); return value;
            });
            GitDiffPanel current = panel; List<TextEditor> views = edt(() -> components(current, TextEditor.class));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!edt(() -> views.stream().allMatch(view -> view.syntax().status().state() == SyntaxStatus.State.READY))) {
                if (System.nanoTime() > until) fail(edt(() -> views.stream().map(view -> view.syntax().status()).toList()).toString()); Thread.sleep(10);
            }
            edt(() -> {
                assertEquals(Set.of(new SyntaxLanguageId("java"), new SyntaxLanguageId("python")), new HashSet<>(views.stream().map(view -> view.syntax().language()).toList()));
                current.clear("No selection"); assertEquals(0, registry.statistics().handles());
                assertTrue(views.stream().allMatch(view -> view.syntax().result() == null)); return null;
            });
        } finally {
            GitDiffPanel value = panel; edt(() -> { if (value != null) value.close(); frame.dispose(); registry.close(); return null; }); workers.shutdownNow();
        }
    }
    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>(); for (Component child : root.getComponents()) { if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container container) found.addAll(components(container, type)); } return found;
    }
    private static <T> T edt(Callable<T> task) throws Exception { FutureTask<T> future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get(); }
}
