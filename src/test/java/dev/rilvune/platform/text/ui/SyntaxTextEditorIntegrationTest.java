package dev.rilvune.platform.text.ui;

import dev.rilvune.platform.text.domain.syntax.*;
import dev.rilvune.platform.text.infrastructure.syntax.rsta.RstaFixtureContribution;
import dev.rilvune.platform.text.infrastructure.syntax.rsta.RstaSyntaxProvider;
import dev.rilvune.platform.text.ui.syntax.*;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.SyntaxColors;
import dev.rilvune.platform.theme.TextHighlightColors;
import dev.rilvune.platform.theme.FontCatalog;
import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.diff.ui.SnapshotDiffDialog;
import dev.rilvune.diff.ui.DiffView;
import dev.rilvune.diff.domain.DiffOptions;
import dev.rilvune.diff.domain.TextSnapshot;
import dev.rilvune.diff.infrastructure.*;
import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui") @Timeout(30)
class SyntaxTextEditorIntegrationTest {
    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }

    @Test void islandsPaletteRendersConsistentlyAcrossExistingLanguageModes() throws Exception {
        var names = List.of("Scheduler.java", "worker.py", "settings.json", "index.html");
        var sources = List.of("""
                package sample;

                /** Keeps pending work visible until it completes. */
                @Deprecated
                public final class Scheduler {
                    private final int retries = 3;
                    private String region = "eu-west-1";

                    // Preserve the task while waiting for approval.
                    public boolean isReady() {
                        return retries > 0;
                    }
                }
                """, """
                from pathlib import Path

                # Process queued work without an arbitrary deadline.
                def process_task(name, retries=3):
                    directory = Path("workspace")
                    if directory.exists():
                        print("Running", name)
                        return True
                    return False
                """, """
                {
                  "application": "Quiet Editor",
                  "retries": 3,
                  "enabled": true,
                  "region": "eu-west-1",
                  "features": ["syntax", "comparison", "search"]
                }
                """, """
                <!DOCTYPE html>
                <!-- Keep structure visible without oversaturating text. -->
                <main class="workspace" data-mode="review">
                  <h1>Review pending changes</h1>
                  <p>Ready &amp; waiting for approval.</p>
                  <button type="button">Continue</button>
                </main>
                """);
        try (Fixture fixture = new Fixture(List.of(new RstaSyntaxProvider().contribution()))) {
            var editors = new ArrayList<TextEditor>();
            try {
                edt(() -> {
                    JPanel grid = new JPanel(new GridLayout(2, 2, 10, 10)); grid.setBackground(fixture.theme.chrome());
                    for (int i = 0; i < names.size(); i++) {
                        var editor = new TextEditor(new ReadOnlyTextModel(sources.get(i)).document(), Optional.empty(), settings(false),
                                fixture.theme, Assertions::fail, TextEditor::standardMenu);
                        editor.installSyntax(fixture.registry, SyntaxSource.file(names.get(i))); editors.add(editor);
                        JPanel group = new JPanel(new BorderLayout()); group.setBackground(fixture.theme.raised());
                        JLabel title = new JLabel(names.get(i)); title.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                        UiTypography.interfaceFont(title, fixture.theme); title.setForeground(fixture.theme.text());
                        group.add(title, BorderLayout.NORTH); group.add(editor); grid.add(group);
                    }
                    fixture.frame.setContentPane(grid); fixture.frame.setSize(1500, 900); fixture.frame.setVisible(true); return null;
                });
                for (var editor : editors) awaitReady(editor);
                edt(() -> {
                    for (int i = 0; i < editors.size(); i++) { assertEquals(sources.get(i), editors.get(i).textComponent().getText()); assertFalse(editors.get(i).editable()); }
                    save(fixture.frame.getRootPane(), "islands-languages"); return null;
                });
            } finally { edt(() -> { editors.forEach(TextEditor::close); return null; }); }
        }
    }

    @Test void islandsDiffAndSearchBackdropsPreserveSourceAndSyntaxPresentation() throws Exception {
        String before = """
                package sample;

                public final class Scheduler {
                    private int retries = 3;
                    private String region = "eu-west-1";

                    // Keep pending work visible until it completes.
                    public boolean isReady() {
                        return retries > 0;
                    }

                    private String legacy = "disabled";
                }
                """;
        String after = before.replace("retries = 3", "retries = 5").replace("eu-west-1", "eu-central-1")
                .replace("    private String legacy = \"disabled\";\n", "")
                .replace("    // Keep", "    private final int timeout = 30;\n\n    // Keep");
        var result = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("before", 0, before),
                new TextSnapshot("after", 1, after), DiffOptions.exact(), () -> false);
        try (Fixture fixture = new Fixture(List.of(new RstaSyntaxProvider().contribution()))) {
            var editors = new ArrayList<TextEditor>(); var views = new ArrayList<DiffView>();
            try {
                edt(() -> {
                    for (String source : List.of(before, after)) {
                        var editor = new TextEditor(new ReadOnlyTextModel(source).document(), Optional.empty(),
                                new TextEditorSettings(4, true, false, true, true, false), fixture.theme, Assertions::fail, TextEditor::standardMenu);
                        editor.installSyntax(fixture.registry, SyntaxSource.file("Scheduler.java")); editors.add(editor);
                    }
                    var view = new DiffView(new DiffView.Source(editors.get(0), editors.get(0)), new DiffView.Source(editors.get(1), editors.get(1)),
                            fixture.theme, Optional.empty(), Optional.empty(), ignored -> { }, Assertions::fail); views.add(view);
                    view.headers("Before", "Read-only source", "After", "Read-only source");
                    fixture.frame.setContentPane(view); fixture.frame.setSize(1500, 650); fixture.frame.setVisible(true); fixture.frame.validate(); view.showResult(result);
                    editors.get(0).caret(before.indexOf("retries")); editors.get(1).caret(after.indexOf("retries"));
                    editors.get(1).find(false);
                    var find = components(editors.get(1), FindBar.class).getFirst(); components(find, JTextField.class).getFirst().setText("retries");
                    return null;
                });
                for (var editor : editors) awaitReady(editor);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!edt(() -> Arrays.stream(editors.get(1).textComponent().getHighlighter().getHighlights()).anyMatch(highlight ->
                        highlight.getPainter() instanceof javax.swing.text.DefaultHighlighter.DefaultHighlightPainter painter && TextHighlightColors.SEARCH.equals(painter.getColor())))) {
                    if (System.nanoTime() > until) fail("Search background did not settle"); Thread.sleep(10);
                }
                edt(() -> {
                    assertEquals(before, editors.get(0).textComponent().getText()); assertEquals(after, editors.get(1).textComponent().getText());
                    save(fixture.frame.getRootPane(), "islands-diff-search"); return null;
                });
            } finally { edt(() -> { views.forEach(DiffView::close); editors.forEach(TextEditor::close); return null; }); }
        }
    }
    @Test void bothCustomContributionRoutesColorActualSharedNormalAndDecoratedTextViews() throws Exception {
        for (var contribution : List.of(FixtureSyntax.contribution(), RstaFixtureContribution.contribution())) {
            try (Fixture fixture = new Fixture(List.of(contribution))) {
                String suffix = contribution.languages().getFirst().associations().getFirst().value();
                String source = "paint \"start\ncontinued\"\n42\n";
                TextEditor[] editors = edt(() -> {
                    PlainDocument model = new PlainDocument(); model.insertString(0, source, null);
                    EditorUndo undo = new EditorUndo(() -> true); model.addUndoableEditListener(undo);
                    TextEditor left = new TextEditor(model, Optional.of(undo), settings(false), fixture.theme, Assertions::fail, TextEditor::standardMenu);
                    TextEditor right = new TextEditor(model, Optional.empty(), settings(true), fixture.theme, Assertions::fail, TextEditor::standardMenu);
                    left.installSyntax(fixture.registry, SyntaxSource.file("sample" + suffix)); right.installSyntax(fixture.registry, SyntaxSource.file("sample" + suffix));
                    right.setLineLayout(new TextLineLayout(Map.of(1, 1), List.of(new TextLineLayout.Fold(3, 4))), ignored -> { });
                    fixture.frame.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)); fixture.frame.setSize(1000, 500); fixture.frame.setVisible(true);
                    ((JSplitPane) fixture.frame.getContentPane().getComponent(0)).setDividerLocation(.5);
                    return new TextEditor[]{left, right};
                });
                try {
                    awaitReady(editors[0]); awaitReady(editors[1]);
                    edt(() -> {
                        assertSame(editors[0].textComponent().getDocument(), editors[1].textComponent().getDocument());
                        assertSame(editors[0].syntax().result(), editors[1].syntax().result());
                        assertEquals(1, fixture.registry.statistics().completed());
                        snapshot(editors[0], fixture.theme, "normal-" + suffix.substring(1)); snapshot(editors[1], fixture.theme, "decorated-" + suffix.substring(1));
                        var expected = editors[0].captureView(); long jobs = fixture.registry.statistics().completed();
                        editors[0].applySettings(settings(true), fixture.theme);
                        assertEquals(expected.dot(), editors[0].captureView().dot()); assertEquals(jobs, fixture.registry.statistics().completed());
                        assertFalse(Arrays.stream(TextEditor.standardMenu(editors[1]).getComponents()).anyMatch(JMenu.class::isInstance));
                        assertEquals(SyntaxSelection.AUTO, editors[1].syntax().selection());
                        editors[1].applySettings(settings(true).withSyntaxHighlighting(false), fixture.theme);
                        assertNull(editors[1].syntax().result()); assertNotNull(editors[0].syntax().result());
                        editors[0].textComponent().insert(" 7", 5); assertNull(editors[0].syntax().result());
                        assertEquals("paint 7 \"start\ncontinued\"\n42\n", editors[1].textComponent().getText());
                        editors[0].undo(); assertEquals(source, editors[0].textComponent().getText());
                        editors[0].applySettings(settings(false).withSyntaxHighlighting(false), fixture.theme);
                        editors[1].applySettings(settings(true).withSyntaxHighlighting(false), fixture.theme);
                        assertNull(editors[0].syntax().result()); return null;
                    });
                } finally { edt(() -> { for (var editor : editors) editor.close(); return null; }); }
                edt(() -> { assertEquals(0, fixture.registry.statistics().handles()); return null; });
            }
        }
    }
    @Test void completeSnapshotsUseIndependentLanguagesWhileUnidentifiedChunksStayPlainAndNeverBecomeEditable() throws Exception {
        try (Fixture fixture = new Fixture(List.of(new RstaSyntaxProvider().contribution()))) {
            SnapshotDiffDialog dialog = edt(() -> {
                var value = new SnapshotDiffDialog(fixture.frame, "Syntax snapshots",
                        new SnapshotDiffDialog.Source("Before", "commit", new TextSnapshot("before", 1, "public class Before {}"), SyntaxSource.file("Before.java")),
                        new SnapshotDiffDialog.Source("After", "snapshot", new TextSnapshot("after", 2, "def after():\n    return 42\n"), SyntaxSource.file("after.py")),
                        settings(true), fixture.theme, new MyersDiffEngine(DiffLimits.interactive()), fixture.workers, Assertions::fail, fixture.registry);
                value.setVisible(true); return value;
            });
            try {
                List<TextEditor> views = edt(() -> components(dialog, TextEditor.class));
                assertEquals(2, views.size()); for (var view : views) awaitReady(view);
                edt(() -> {
                    assertEquals(new SyntaxLanguageId("java"), views.get(0).syntax().language());
                    assertEquals(new SyntaxLanguageId("python"), views.get(1).syntax().language());
                    for (var view : views) {
                        String original = view.textComponent().getText(); view.textComponent().replaceRange("forbidden", 0, 1);
                        assertEquals(original, view.textComponent().getText()); assertFalse(view.editable());
                    }
                    return null;
                });
            } finally { edt(() -> { dialog.dispose(); dialog.dispose(); assertEquals(0, fixture.registry.statistics().handles()); return null; }); }
            edt(() -> {
                var chunks = new SnapshotDiffDialog(fixture.frame, "Unverified chunks",
                        new SnapshotDiffDialog.Source("Before", "not a filename", new TextSnapshot("a", 0, "paint")),
                        new SnapshotDiffDialog.Source("After", "not a filename", new TextSnapshot("b", 0, "paint 42")),
                        settings(false), fixture.theme, new MyersDiffEngine(DiffLimits.interactive()), fixture.workers, Assertions::fail, fixture.registry);
                assertEquals(0, fixture.registry.statistics().handles()); chunks.dispose(); return null;
            });
        }
    }
    private static TextEditorSettings settings(boolean wrap) { return new TextEditorSettings(4, true, wrap, true, false, false, true); }
    private static void awaitReady(TextEditor editor) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!edt(() -> editor.syntax().status().state() == SyntaxStatus.State.READY)) {
            if (System.nanoTime() > until) fail(edt(() -> editor.syntax().status()).toString()); Thread.sleep(10);
        }
    }
    private static void snapshot(TextEditor editor, EditorTheme theme, String name) throws Exception {
        JTextArea area = editor.textComponent(); var image = new BufferedImage(area.getWidth(), area.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); area.printAll(graphics); graphics.dispose();
        int color = SyntaxColors.color(SyntaxKind.KEYWORD, theme).getRGB(); boolean found = false;
        for (int y = 0; y < image.getHeight() && !found; y++) for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) == color) { found = true; break; }
        assertTrue(found, "No actual keyword-colored glyphs"); Path output = Path.of("build/ui-snapshots/syntax-" + name + ".png");
        Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
    }
    private static void save(JComponent component, String name) throws Exception {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); component.printAll(graphics); graphics.dispose();
        Path output = Path.of("build/ui-snapshots/syntax-" + name + ".png"); Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
    }
    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>(); for (Component child : root.getComponents()) { if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container container) found.addAll(components(container, type)); } return found;
    }
    private static final class Fixture implements AutoCloseable {
        final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        final SyntaxProjectionRegistry registry;
        final EditorTheme theme = EditorTheme.graphite();
        final JFrame frame;
        Fixture(List<SyntaxContribution> contributions) throws Exception {
            var l = SyntaxLimits.interactive();
            registry = new SyntaxProjectionRegistry(new SyntaxCatalog(contributions), new SyntaxLimits(l.characters(), l.lines(), l.lineCharacters(), l.spans(), 2, 256, 512,
                    l.payloadBytes(), Duration.ofMillis(10), Duration.ofSeconds(5)), workers);
            frame = edt(() -> { theme.install(); return new JFrame("Syntax integration"); });
        }
        public void close() throws Exception { edt(() -> { frame.dispose(); registry.close(); return null; }); workers.shutdownNow(); }
    }
    private static <T> T edt(Callable<T> task) throws Exception { FutureTask<T> future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get(); }
}
