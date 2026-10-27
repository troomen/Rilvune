package dev.rilvune.platform.text.ui.minimap;

import dev.rilvune.platform.text.ui.*;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
@Timeout(20)
class TextMinimapIntegrationTest {
    private JFrame frame;
    private TextEditor editor;
    private TextMinimap minimap;
    private final EditorTheme theme = EditorTheme.graphite();
    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }
    @AfterEach void close() throws Exception { edt(() -> { if (editor != null) editor.close(); if (frame != null) frame.dispose(); return null; }); }

    private void start(String source, boolean wrap) throws Exception {
        edt(() -> {
            theme.install();
            editor = new TextEditor(new ReadOnlyTextModel(source).document(), Optional.empty(), settings(wrap, true),
                    theme, message -> fail(message), TextEditor::standardMenu);
            minimap = (TextMinimap)Arrays.stream(editor.getComponents()).filter(TextMinimap.class::isInstance).findFirst().orElseThrow();
            frame = new JFrame("Document minimap"); frame.setContentPane(editor); frame.setSize(1000, 640); frame.setVisible(true); frame.validate();
            editor.textComponent().select(4, 10); return null;
        });
        await(() -> inkUnchecked() > 50);
    }

    @Test void previewRendersRealTextAndMouseNavigationPreservesSourceAndSelection() throws Exception {
        String source = "public void documentLine() { return value; }\n".repeat(700);
        start(source, false);
        edt(() -> {
            JTextArea text = editor.textComponent();
            int caret = text.getCaretPosition(), mark = text.getCaret().getMark();
            click(minimap.getHeight() - 1);
            assertEquals(text.getHeight() - editor.scrollPane().getViewport().getExtentSize().height,
                    editor.scrollPane().getViewport().getViewPosition().y);
            assertEquals(caret, text.getCaretPosition()); assertEquals(mark, text.getCaret().getMark());
            click(0); assertEquals(0, editor.scrollPane().getViewport().getViewPosition().y);
            MinimapGeometry geometry = geometry();
            int y = geometry.viewport(0, editor.scrollPane().getViewport().getExtentSize().height).height / 2;
            int before = editor.scrollPane().getViewport().getViewPosition().y;
            mouse(MouseEvent.MOUSE_PRESSED, y, MouseEvent.BUTTON1);
            assertEquals(before, editor.scrollPane().getViewport().getViewPosition().y, "grabbing the viewport must not jump");
            mouse(MouseEvent.MOUSE_DRAGGED, y + 100, MouseEvent.NOBUTTON);
            mouse(MouseEvent.MOUSE_RELEASED, y + 100, MouseEvent.BUTTON1);
            assertTrue(editor.scrollPane().getViewport().getViewPosition().y > 0);
            assertEquals(source, text.getText());
            editor.scrollPane().getViewport().setViewPosition(new Point());
            frame.validate();
            BufferedImage screenshot = render(editor); Path file = Path.of("build/ui-snapshots/text-minimap.png");
            Files.createDirectories(file.getParent()); ImageIO.write(screenshot, "png", file.toFile());
            return null;
        });
    }

    @Test void wrappingFoldsPaddingAndLiveVisibilityUseActualEditorGeometry() throws Exception {
        String source = ("long wrapped line " + "content ".repeat(60) + "\n").repeat(200);
        start(source, true);
        edt(() -> {
            editor.setLineLayout(new TextLineLayout(Map.of(100, 20), java.util.List.of(new TextLineLayout.Fold(20, 80))), ignored -> { });
            frame.validate(); return null;
        });
        await(() -> inkUnchecked() > 30);
        edt(() -> {
            int target = (int)editor.textComponent().modelToView2D(editor.textComponent().getLineStartOffset(120)).getY();
            MinimapGeometry geometry = geometry();
            int extent = editor.scrollPane().getViewport().getExtentSize().height;
            int y = (int)Math.round((target + extent / 2.0) * geometry.scale());
            click(y);
            assertEquals(target, editor.scrollPane().getViewport().getViewPosition().y, 1.0 / geometry.scale() + 1);
            editor.applySettings(settings(true, false), theme); frame.validate(); assertFalse(minimap.isVisible());
            editor.applySettings(settings(true, true), theme); frame.validate(); assertTrue(minimap.isShowing());
            assertEquals(source, editor.textComponent().getText()); return null;
        });
        await(() -> inkUnchecked() > 30);
    }

    @Test void largeDocumentsReadOnlyBoundedSamplesAndCloseRemovesItsListeners() throws Exception {
        var document = new CountingDocument();
        document.insertString(0, "long-line-".repeat(20_000) + "\n" + "sample text\n".repeat(50_000), null);
        edt(() -> {
            theme.install(); editor = new TextEditor(document, Optional.empty(), settings(false, true), theme, ignored -> { }, TextEditor::standardMenu);
            minimap = (TextMinimap)Arrays.stream(editor.getComponents()).filter(TextMinimap.class::isInstance).findFirst().orElseThrow();
            frame = new JFrame(); frame.setContentPane(editor); frame.setSize(900, 600); frame.setVisible(true); frame.validate(); return null;
        });
        await(() -> inkUnchecked() > 50);
        edt(() -> {
            assertTrue(document.maximumRead <= 256, "minimap must not copy entire lines or documents: " + document.maximumRead);
            editor.scrollPane().getViewport().setViewPosition(new Point(300, 0));
            int horizontal = editor.scrollPane().getViewport().getViewPosition().x;
            click(minimap.getHeight() - 1);
            assertEquals(horizontal, editor.scrollPane().getViewport().getViewPosition().x);
            document.remove(0, document.getLength()); document.insertString(0, "\n".repeat(800), null);
            return null;
        });
        await(() -> inkUnchecked() == 0);
        edt(() -> {
            document.insertString(0, "replacement preview text\n".repeat(800), null); return null;
        });
        await(() -> inkUnchecked() > 50);
        edt(() -> {
            int edits = document.getDocumentListeners().length;
            int properties = editor.textComponent().getPropertyChangeListeners().length;
            int scrolling = editor.scrollPane().getViewport().getChangeListeners().length;
            minimap.close(); minimap.close();
            assertEquals(edits - 1, document.getDocumentListeners().length);
            assertEquals(properties - 1, editor.textComponent().getPropertyChangeListeners().length);
            assertEquals(scrolling - 1, editor.scrollPane().getViewport().getChangeListeners().length);
            return null;
        });
    }

    private TextEditorSettings settings(boolean wrap, boolean visible) { return new TextEditorSettings(4, true, wrap, true, false, visible); }
    private MinimapGeometry geometry() { return MinimapGeometry.of(minimap.getWidth(), minimap.getHeight(), editor.textComponent().getHeight(), Math.min(.25, 1.5 / editor.textComponent().getFont().getSize2D())); }
    private void click(int y) { mouse(MouseEvent.MOUSE_PRESSED, y, MouseEvent.BUTTON1); mouse(MouseEvent.MOUSE_RELEASED, y, MouseEvent.BUTTON1); }
    private void mouse(int id, int y, int button) { minimap.dispatchEvent(new MouseEvent(minimap, id, System.currentTimeMillis(), id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK, 20, y, 1, false, button)); }
    private int inkUnchecked() {
        try { return edt(() -> {
            BufferedImage image = render(minimap); int ink = 0;
            for (int y = 5; y < image.getHeight(); y++) for (int x = 4; x < image.getWidth() - 4; x++) {
                Color color = new Color(image.getRGB(x, y));
                if (color.getRed() > 55 && Math.abs(color.getRed() - color.getBlue()) < 20 && Math.abs(color.getGreen() - color.getBlue()) < 20) ink++;
            }
            return ink;
        }); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static BufferedImage render(JComponent component) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); try { component.printAll(g); } finally { g.dispose(); } return image;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(30); }
        fail("Minimap did not render in time");
    }
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    private static final class CountingDocument extends PlainDocument {
        int maximumRead;
        @Override public String getText(int offset, int length) throws BadLocationException { maximumRead = Math.max(maximumRead, length); return super.getText(offset, length); }
    }
}
