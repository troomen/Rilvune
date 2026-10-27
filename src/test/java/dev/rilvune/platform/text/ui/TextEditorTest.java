package dev.rilvune.platform.text.ui;

import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.PlainDocument;
import java.awt.event.ActionEvent;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TextEditorTest {
    private static final TextEditorSettings SETTINGS = new TextEditorSettings(4, true, false, true, true, true);

    @Test void snapshotStaysReadOnlyThroughCommandsInheritedActionsAndDirectTextMutation() throws Exception {
        edt(() -> {
            ReadOnlyTextModel snapshot = new ReadOnlyTextModel("immutable\n");
            try (TextEditor editor = new TextEditor(snapshot.document(), Optional.empty(), SETTINGS,
                    EditorTheme.graphite(), message -> fail(message), TextEditor::standardMenu)) {
                editor.textComponent().setEditable(true);
                assertFalse(editor.textComponent().isEditable());
                editor.textComponent().replaceRange("changed", 0, 9);
                ((AbstractDocument) snapshot.document()).replace(0, 9, "changed", null);
                editor.newLine(); editor.indent(false); editor.moveLines(1); editor.undo(); editor.redo();
                editor.textComponent().getActionMap().get("indent").actionPerformed(new ActionEvent(editor, 0, "indent"));
                assertEquals("immutable\n", editor.textComponent().getText());
                JPopupMenu menu = editor.textComponent().getComponentPopupMenu();
                var labels = Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                        .map(JMenuItem.class::cast).map(JMenuItem::getText).toList();
                assertTrue(labels.contains("Copy")); assertTrue(labels.contains("Find…"));
                assertFalse(labels.contains("Paste")); assertFalse(labels.contains("Undo")); assertFalse(labels.contains("Replace…"));
                snapshot.replaceSnapshot("refreshed snapshot\n");
                editor.textComponent().replaceRange("forbidden", 0, 9);
                assertEquals("refreshed snapshot\n", editor.textComponent().getText());
            }
            return null;
        });
    }

    @Test void extractedIndentationAndLineCommandsKeepTheirAtomicUndoBehavior() throws Exception {
        edt(() -> {
            PlainDocument model = new PlainDocument(); model.insertString(0, "alpha\nbeta\n", null);
            EditorUndo undo = new EditorUndo(() -> true); model.addUndoableEditListener(undo);
            try (TextEditor editor = new TextEditor(model, Optional.of(undo), SETTINGS,
                    EditorTheme.graphite(), message -> fail(message), TextEditor::standardMenu)) {
                editor.caret(0); editor.indent(false);
                assertEquals("    alpha\nbeta\n", editor.textComponent().getText());
                editor.undo(); assertEquals("alpha\nbeta\n", editor.textComponent().getText());
                editor.caret(7); editor.moveLines(-1);
                assertEquals("beta\nalpha\n", editor.textComponent().getText());
                editor.undo(); assertEquals("alpha\nbeta\n", editor.textComponent().getText());
                editor.caret(5); editor.newLine();
                assertEquals("alpha\n\nbeta\n", editor.textComponent().getText());
                editor.undo(); assertEquals("alpha\nbeta\n", editor.textComponent().getText());
            }
            return null;
        });
    }

    @Test void contextFactoriesAndKeyBindingsAreLocalToEachTextView() throws Exception {
        edt(() -> {
            PlainDocument model = new PlainDocument(); model.insertString(0, "source", null);
            EditorUndo undo = new EditorUndo(() -> true); model.addUndoableEditListener(undo);
            try (TextEditor first = new TextEditor(model, Optional.of(undo), SETTINGS, EditorTheme.graphite(),
                    message -> fail(message), view -> { JPopupMenu menu = new JPopupMenu(); menu.add("Host action"); return menu; });
                 TextEditor second = new TextEditor(model, Optional.of(undo), SETTINGS, EditorTheme.graphite(),
                         message -> fail(message), TextEditor::standardMenu)) {
                assertEquals("Host action", ((JMenuItem) first.textComponent().getComponentPopupMenu().getComponent(0)).getText());
                assertEquals("Undo", ((JMenuItem) second.textComponent().getComponentPopupMenu().getComponent(0)).getText());
                first.textComponent().getActionMap().put("indent", new javax.swing.AbstractAction() {
                    @Override public void actionPerformed(ActionEvent event) { fail("Another view's action leaked"); }
                });
                second.caret(0);
                second.textComponent().getActionMap().get("indent").actionPerformed(new ActionEvent(second, 0, "indent"));
                assertEquals("    source", second.textComponent().getText());
            }
            return null;
        });
    }

    @Test void wordWrapSettingsPreserveSelectionAndNeverEditSharedTextOrUndo() throws Exception {
        edt(() -> {
            String source = "Long words and whitespace ".repeat(30);
            PlainDocument model = new PlainDocument(); model.insertString(0, source, null);
            EditorUndo undo = new EditorUndo(() -> true); model.addUndoableEditListener(undo);
            try (TextEditor first = new TextEditor(model, Optional.of(undo), SETTINGS, EditorTheme.graphite(),
                    message -> fail(message), TextEditor::standardMenu);
                 TextEditor second = new TextEditor(model, Optional.of(undo), SETTINGS, EditorTheme.graphite(),
                         message -> fail(message), TextEditor::standardMenu)) {
                first.textComponent().select(5, 15);
                first.applySettings(SETTINGS.withSoftWrap(true), EditorTheme.graphite());
                second.applySettings(SETTINGS.withSoftWrap(true), EditorTheme.graphite());
                assertTrue(first.wordWrap()); assertTrue(first.settings().softWrap());
                assertTrue(first.textComponent().getLineWrap()); assertTrue(first.textComponent().getWrapStyleWord());
                assertTrue(second.wordWrap());
                assertEquals(source, first.textComponent().getText());
                assertEquals(source.substring(5, 15), first.textComponent().getSelectedText());
                assertFalse(undo.canUndo());
                first.applySettings(new TextEditorSettings(8, false, false, false, false, false), EditorTheme.graphite());
                assertFalse(first.wordWrap()); assertEquals(8, first.settings().tabSize());
                assertEquals(source, model.getText(0, model.getLength())); assertFalse(undo.canUndo());
            }
            return null;
        });
    }

    @Test void readOnlyTextFollowsWordWrapSettingsWithoutALocalOverride() throws Exception {
        edt(() -> {
            var model = new ReadOnlyTextModel("Read-only long text ".repeat(20));
            try (TextEditor editor = new TextEditor(model.document(), Optional.empty(), SETTINGS, EditorTheme.graphite(),
                    message -> fail(message), TextEditor::standardMenu)) {
                editor.applySettings(SETTINGS.withSoftWrap(true), EditorTheme.graphite());
                assertTrue(editor.wordWrap());
                editor.applySettings(SETTINGS, EditorTheme.graphite()); assertFalse(editor.wordWrap());
                assertFalse(editor.editable());
                assertEquals("Read-only long text ".repeat(20), editor.textComponent().getText());
            }
            return null;
        });
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}
