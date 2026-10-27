package dev.rilvune.platform.text.ui;

import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("ui")
class EditorGutterTest {
    @Test void markersRemainVisibleWithoutNumbersAndInvalidSourceRangesCannotBeClicked() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TextEditor editor = new TextEditor(new ReadOnlyTextModel("one\ntwo\n").document(), Optional.empty(), new TextEditorSettings(4, true, true, false, false, true), EditorTheme.graphite(), ignored -> { }, TextEditor::standardMenu);
            JFrame frame = new JFrame(); frame.setContentPane(editor); frame.setSize(600, 400); frame.setVisible(true); frame.validate();
            try {
            var marker = new GutterPresentation.Marker(7, 0, 4, Color.BLUE, "Modified");
            var deletion = new GutterPresentation.Marker(8, 8, 8, Color.GRAY, "Deleted at EOF");
            editor.setGutterPresentation(new GutterPresentation(List.of(marker, deletion), List.of(), false, ""), GutterActions.none());
            EditorGutter gutter = editor.gutter(); gutter.setSize(gutter.getPreferredSize());
            assertEquals(12, gutter.getPreferredSize().width); assertSame(gutter, editor.scrollPane().getRowHeader().getView());
            Rectangle bounds = gutter.markerBounds(marker); assertFalse(bounds.isEmpty());
            assertEquals(7, gutter.markerAt(new Point(bounds.x + 5, bounds.y + 2)).orElseThrow().id());
            assertFalse(gutter.markerBounds(deletion).isEmpty());
            assertTrue(gutter.markerBounds(new GutterPresentation.Marker(9, 20, 30, Color.RED, "Stale")).isEmpty());
            } finally { editor.close(); frame.dispose(); }
        });
    }
}
