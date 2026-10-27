package dev.editor.diff.ui;

import dev.editor.diff.domain.*;
import dev.editor.diff.infrastructure.*;
import dev.editor.platform.text.ui.*;
import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.*;
import javax.swing.*;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
class DiffLineLayoutIntegrationTest {
    @Test void alignmentAndFoldingAreViewsOverUnmodifiedSourceDocuments() throws Exception {
        FutureTask<Void> test = new FutureTask<>(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            String a = "same\n".repeat(20) + "old\n" + "suffix\n".repeat(20);
            String b = "same\n".repeat(20) + "new\ninserted\n" + "suffix\n".repeat(20);
            var left = new TextEditor(new ReadOnlyTextModel(a).document(), Optional.empty(), new TextEditorSettings(4, true, false, true, false, true), theme, ignored -> { }, TextEditor::standardMenu);
            var right = new TextEditor(new ReadOnlyTextModel(b).document(), Optional.empty(), left.settings(), theme, ignored -> { }, TextEditor::standardMenu);
            var diff = new DiffView(new DiffView.Source(left, left), new DiffView.Source(right, right), theme, Optional.empty(), Optional.empty(), ignored -> { }, ignored -> { });
            JFrame frame = new JFrame(); frame.setContentPane(diff); frame.setSize(1100, 700); frame.setVisible(true);
            try {
                DiffResult result = new MyersDiffEngine(DiffLimits.interactive()).compare(new TextSnapshot("a", 0, a), new TextSnapshot("b", 0, b), DiffOptions.exact(), () -> false);
                diff.showResult(result); frame.validate();
                int leftSuffix = a.indexOf("suffix"), rightSuffix = b.indexOf("suffix");
                double first = left.textComponent().modelToView2D(leftSuffix).getY(), second = right.textComponent().modelToView2D(rightSuffix).getY();
                assertEquals(first, second, 1, "unchanged suffix starts at the same visual row");
                int expanded = left.textComponent().getPreferredSize().height;
                diff.compactContext(true); frame.validate(); assertTrue(left.textComponent().getPreferredSize().height < expanded);
                assertEquals(a, left.textComponent().getText()); assertEquals(b, right.textComponent().getText());
                left.textComponent().selectAll(); assertEquals(a, left.textComponent().getSelectedText(), "copy contains only complete source text");
                left.caret(a.indexOf("same", 30)); frame.validate();
                assertTrue(left.textComponent().modelToView2D(left.textComponent().getCaretPosition()).getHeight() > 0, "caret navigation expands hidden source");
                left.applySettings(new TextEditorSettings(4, true, true, true, false, true), theme); frame.validate();
                assertNotNull(left.textComponent().modelToView2D(leftSuffix)); assertEquals(a, left.textComponent().getText());
                diff.invalidateResult(); assertEquals(a, left.textComponent().getText());
            } finally { diff.close(); left.close(); right.close(); frame.dispose(); }
            return null;
        }); SwingUtilities.invokeAndWait(test); test.get();
    }
}
