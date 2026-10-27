package dev.editor.diff.ui;

import dev.editor.diff.domain.*;
import dev.editor.platform.text.ui.*;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import java.awt.BorderLayout;
import java.util.Optional;
import java.util.function.Consumer;

/** Read-only original source for one side of a hunk, with the shared readability-oriented diff highlights. */
public final class DiffSidePreview extends JPanel implements AutoCloseable {
    private TextEditor editor;
    private DiffHighlights highlights;

    public DiffSidePreview(DiffResult diff, int index, Side side, TextEditorSettings settings,
                           EditorTheme theme, Consumer<String> report) {
        super(new BorderLayout());
        TextRange range = side == Side.LEFT ? diff.hunks().get(index).left() : diff.hunks().get(index).right();
        if (range.empty()) return;
        if (range.endLine() - range.firstLine() > 200) {
            JLabel notice = new JLabel("This block exceeds 200 lines. Open Full Diff to inspect it.");
            notice.setForeground(theme.text()); notice.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            add(notice); return;
        }
        String source = (side == Side.LEFT ? diff.left() : diff.right()).text().substring(range.startOffset(), range.endOffset());
        var presentation = new TextEditorSettings(settings.tabSize(), settings.useSpaces(), settings.softWrap(), false, false, false, settings.syntaxHighlighting());
        editor = new TextEditor(new ReadOnlyTextModel(source).document(), Optional.empty(), presentation, theme, report, TextEditor::standardMenu);
        editor.textComponent().getAccessibleContext().setAccessibleName("Change source preview · read only");
        editor.textComponent().setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        highlights = new DiffHighlights(editor, false); highlights.showHunk(diff, index, side, range.startOffset());
        add(editor);
    }
    @Override public void close() {
        if (highlights != null) highlights.close();
        if (editor != null) editor.close();
    }
}
