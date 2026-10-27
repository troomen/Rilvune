package dev.editor.platform.text.ui;

import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.text.View;
import java.util.*;
import java.util.concurrent.FutureTask;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
class TextLineLayoutSnapshotTest {
    @Test void replacingSnapshotsKeepsRealLineViewsAndCorrectLineIndices() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            ReadOnlyTextModel model = new ReadOnlyTextModel("original line\n".repeat(80));
            TextEditor editor = new TextEditor(model.document(), Optional.empty(), new TextEditorSettings(4, true, false, true, false, true), theme, ignored -> { }, TextEditor::standardMenu);
            JFrame frame = new JFrame(); frame.add(editor); frame.setSize(800, 500); frame.setVisible(true);
            try {
                editor.setLineLayout(new TextLineLayout(Map.of(5, 1), List.of()), ignored -> { });
                for (int count : new int[]{20, 200, 4, 90}) {
                    String snapshot = "replacement line\n".repeat(count); model.replaceSnapshot(snapshot);
                    editor.setLineLayout(new TextLineLayout(Map.of(2, 2), List.of()), ignored -> { }); frame.validate();
                    View lines = editor.textComponent().getUI().getRootView(editor.textComponent()).getView(0);
                    assertEquals(editor.textComponent().getLineCount(), lines.getViewCount());
                    for (int line = 0; line < lines.getViewCount(); line++) assertEquals(1, lines.getView(line).getViewCount(), "Every source line must retain its text view after a replacement");
                    var before = editor.textComponent().modelToView2D(editor.textComponent().getLineStartOffset(1));
                    var after = editor.textComponent().modelToView2D(editor.textComponent().getLineStartOffset(2));
                    int height = editor.textComponent().getFontMetrics(editor.textComponent().getFont()).getHeight();
                    assertEquals(height * 3, after.getY() - before.getY(), 1, "Padding belongs to the current source line");
                    assertEquals(snapshot, editor.textComponent().getText());
                }
            } finally { editor.close(); frame.dispose(); }
            return null;
        }); SwingUtilities.invokeAndWait(task); task.get();
    }
}
