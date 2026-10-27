package dev.rilvune.diff.ui;

import dev.rilvune.diff.domain.*;
import dev.rilvune.diff.infrastructure.DiffLimits;
import dev.rilvune.diff.infrastructure.MyersDiffEngine;
import dev.rilvune.platform.text.ui.ReadOnlyTextModel;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import org.junit.jupiter.api.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(20)
class DiffPresentationIntegrationTest {
    private JFrame frame;
    private TextEditor left;
    private TextEditor right;
    private DiffView view;
    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }
    @AfterEach void close() throws Exception {
        edt(() -> { if (view != null) view.close(); if (left != null) left.close(); if (right != null) right.close(); if (frame != null) frame.dispose(); return null; });
    }
    @Test void nativePaintingKeepsFineNumericDetailAndDirectionalColorsInBothModes() throws Exception {
        String before = "port = 8080\nworkers = 2\nobsolete = true\nmode = keep\nname = foo;\n# end\n";
        String after = "port = 9090\ntimeout = 30\nworkers = 4\nmode = keep\nname = fooBar;\n# end\n";
        DiffResult result = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("a", 0, before), new TextSnapshot("b", 0, after), DiffOptions.exact(), () -> false);
        EditorTheme theme = EditorTheme.graphite();
        edt(() -> {
            theme.install(); left = editor(before, theme); right = editor(after, theme);
            view = new DiffView(new DiffView.Source(left, left), new DiffView.Source(right, right), theme, Optional.empty(), Optional.empty(), ignored -> { }, message -> fail(message));
            view.headers("Before", "Fixed snapshot", "After", "Fixed snapshot");
            frame = new JFrame("Shared snapshot diff"); frame.setContentPane(view); frame.setSize(1200, 650); frame.setVisible(true); frame.validate(); view.showResult(result); return null;
        });
        edt(() -> null);
        edt(() -> {
            assertBackground(left, before.indexOf("8080"), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(right, after.indexOf("9090"), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(left, before.indexOf("8080") + 1, theme.surface());
            assertBackground(right, after.indexOf("9090") + 1, theme.surface());
            assertBackground(left, before.indexOf("port") + 2, theme.surface());
            assertWholeLine(left, before.indexOf("obsolete"), DiffColors.fill(DiffKind.REMOVED));
            assertWholeLine(right, after.indexOf("timeout"), DiffColors.fill(DiffKind.ADDED));
            assertBackground(right, after.indexOf("Bar"), DiffColors.fill(DiffKind.ADDED));
            snapshot(view, Path.of("build/ui-snapshots/refined-diff.png"));
            view.restoreOptions(true, false); frame.validate(); return null;
        });
        edt(() -> null);
        edt(() -> {
            TextEditor unified = view.textTarget().orElseThrow(); String text = unified.textComponent().getText();
            assertFalse(unified.editable());
            assertBackground(unified, text.indexOf("8080"), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(unified, text.indexOf("9090") + 1, theme.surface());
            assertWholeLine(unified, text.indexOf("obsolete"), DiffColors.fill(DiffKind.REMOVED));
            assertWholeLine(unified, text.indexOf("timeout"), DiffColors.fill(DiffKind.ADDED));
            assertBackground(unified, text.indexOf("Bar"), DiffColors.fill(DiffKind.ADDED));
            assertEquals(before, left.textComponent().getText()); assertEquals(after, right.textComponent().getText());
            snapshot(view, Path.of("build/ui-snapshots/refined-unified.png")); return null;
        });
    }
    @Test void methodCallRewritePaintsCoherentBlueRegionsInBothModes() throws Exception {
        String before = "label.setFont(theme.interfaceFont().deriveFont(style, 12f));\n";
        String after = "UiTypography.interfaceFontAtSize(label, theme, style, 12);\n";
        DiffResult result = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("a", 0, before),
                new TextSnapshot("b", 0, after), DiffOptions.exact(), () -> false);
        EditorTheme theme = EditorTheme.graphite();
        edt(() -> {
            theme.install(); left = editor(before, theme); right = editor(after, theme);
            view = new DiffView(new DiffView.Source(left, left), new DiffView.Source(right, right), theme,
                    Optional.empty(), Optional.empty(), ignored -> { }, message -> fail(message));
            frame = new JFrame("Readable inline replacements"); frame.setContentPane(view); frame.setSize(1500, 340);
            frame.setVisible(true); frame.validate(); view.showResult(result); return null;
        });
        edt(() -> null);
        edt(() -> {
            assertBackground(left, before.indexOf("label"), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(left, before.indexOf('.'), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(right, after.indexOf("AtSize"), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(left, before.indexOf("theme"), theme.surface());
            assertBackground(right, after.indexOf("style"), theme.surface());
            snapshot(view, Path.of("build/ui-snapshots/readable-inline-diff.png"));
            view.restoreOptions(true, false); frame.validate(); return null;
        });
        edt(() -> null);
        edt(() -> {
            TextEditor unified = view.textTarget().orElseThrow(); String text = unified.textComponent().getText();
            assertBackground(unified, text.indexOf("AtSize"), DiffColors.fill(DiffKind.CHANGED));
            assertBackground(unified, text.indexOf("theme"), theme.surface());
            assertEquals(before, left.textComponent().getText()); assertEquals(after, right.textComponent().getText());
            return null;
        });
    }
    @Test void oneSidedPreviewsAndPopupOwnedHighlightsUseTheSharedPalette() throws Exception {
        String[][] cases = {{"port = 8080\n", "port = 9090\n"}, {"removed\n", ""}, {"", "added\n"}};
        EditorTheme theme = EditorTheme.graphite();
        edt(() -> { theme.install(); frame = new JFrame("One-sided preview"); frame.setSize(1000, 400); frame.setVisible(true); return null; });
        for (int index = 0; index < cases.length; index++) {
            String before = cases[index][0], after = cases[index][1]; int scenario = index;
            DiffResult result = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("a", 0, before), new TextSnapshot("b", 0, after), DiffOptions.exact(), () -> false);
            edt(() -> {
                try (var preview = new DiffSidePreview(result, 0, Side.LEFT, new TextEditorSettings(4, true, false, true, false, true), theme, message -> fail(message));
                     var current = editor(after, theme);
                     var highlights = new DiffHighlights(current, false)) {
                    JPanel content = new JPanel(new GridLayout(1, 2)); content.add(preview); content.add(current);
                    frame.setContentPane(content); frame.validate();
                    var presentation = new dev.rilvune.platform.text.ui.GutterPresentation(java.util.List.of(new dev.rilvune.platform.text.ui.GutterPresentation.Marker(0, 0, after.length(), Color.MAGENTA, "Existing gutter")), java.util.List.of(), false, "");
                    current.setGutterPresentation(presentation, dev.rilvune.platform.text.ui.GutterActions.none());
                    highlights.showHunk(result, 0, Side.RIGHT, 0);
                    if (scenario == 0) {
                        var old = (TextEditor)preview.getComponent(0);
                        assertEquals(before, old.textComponent().getText()); assertFalse(old.editable());
                        assertBackground(old, before.indexOf("8080"), DiffColors.fill(DiffKind.CHANGED));
                        assertBackground(current, after.indexOf("9090"), DiffColors.fill(DiffKind.CHANGED));
                        assertBackground(old, before.indexOf("8080") + 1, theme.surface());
                        assertBackground(current, after.indexOf("9090") + 1, theme.surface());
                        assertBackground(old, 0, theme.surface());
                    } else if (scenario == 1) {
                        var old = (TextEditor)preview.getComponent(0);
                        assertEquals(before, old.textComponent().getText()); assertWholeLine(old, 0, DiffColors.fill(DiffKind.REMOVED));
                        assertEquals(0, current.textComponent().getHighlighter().getHighlights().length);
                    } else {
                        assertEquals(0, preview.getComponentCount());
                        assertWholeLine(current, 0, DiffColors.fill(DiffKind.ADDED));
                    }
                    assertEquals(presentation, current.gutter().presentation());
                    Object unrelated = after.isEmpty() ? null : current.textComponent().getHighlighter().addHighlight(0, 1, new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW));
                    highlights.close();
                    assertEquals(unrelated == null ? 0 : 1, current.textComponent().getHighlighter().getHighlights().length);
                    assertEquals(presentation, current.gutter().presentation());
                }
                return null;
            });
        }
    }
    private TextEditor editor(String text, EditorTheme theme) {
        return new TextEditor(new ReadOnlyTextModel(text).document(), Optional.empty(), new TextEditorSettings(4, true, false, true, false, true), theme, message -> fail(message), TextEditor::standardMenu);
    }
    private void assertBackground(TextEditor editor, int offset, Color expected) throws Exception {
        JTextArea text = editor.textComponent(); Rectangle2D first = text.modelToView2D(offset), next = text.modelToView2D(offset + 1);
        BufferedImage image = render(text);
        int x = (int) ((first.getX() + next.getX()) / 2), y = (int) first.getMaxY() - 2;
        assertEquals(expected.getRGB(), image.getRGB(x, y), "Background at source offset " + offset + " in " + text.getText());
    }
    private void assertWholeLine(TextEditor editor, int offset, Color expected) throws Exception {
        JTextArea text = editor.textComponent(); Rectangle2D bounds = text.modelToView2D(offset); BufferedImage image = render(text);
        assertEquals(expected.getRGB(), image.getRGB(text.getWidth() - 8, (int) bounds.getMaxY() - 2));
    }
    private BufferedImage render(JComponent component) {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); component.printAll(graphics); graphics.dispose(); return image;
    }
    private void snapshot(JComponent component, Path path) throws Exception {
        Files.createDirectories(path.getParent()); javax.imageio.ImageIO.write(render(component), "png", path.toFile());
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task); SwingUtilities.invokeAndWait(result); return result.get(10, TimeUnit.SECONDS);
    }
}
