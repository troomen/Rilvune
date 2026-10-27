package dev.editor.git.ui.editor;

import dev.editor.platform.theme.UiTypography;
import dev.editor.diff.domain.*;
import dev.editor.diff.ui.DiffSidePreview;
import dev.editor.diff.ui.DiffHighlights;
import dev.editor.platform.text.ui.TextEditor;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.geom.Rectangle2D;
import java.util.function.Consumer;

/** Non-modal, bounded inspection. Only the explicitly supplied revert callback can request an edit. */
final class ChangePopup implements AutoCloseable {
    private final EditorTheme theme;
    private final JPopupMenu popup = new JPopupMenu();
    private final DiffSidePreview preview;
    private final DiffHighlights currentHighlights;
    private final int previousLines;
    private boolean closed;
    ChangePopup(TextEditor editor, DiffResult result, int hunk, String baseline, EditorTheme theme, boolean canRevert,
                Runnable revert, Runnable full, Runnable previous, Runnable next, Consumer<String> report) {
        this.theme = theme;
        popup.setName("git.change.popup"); popup.setLayout(new BorderLayout()); popup.setBorder(BorderFactory.createLineBorder(theme.border()));
        JPanel bar = new JPanel(new BorderLayout()); bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        JLabel title = new JLabel("HEAD · " + baseline + " · Previous version"); title.setForeground(theme.text()); UiTypography.interfaceFont(title, theme); bar.add(title);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        button(actions, "↑", "git.change.previous", previous, true); button(actions, "↓", "git.change.next", next, true);
        button(actions, "Copy Previous Text", "git.change.copy", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(result.leftLines().text(result.hunks().get(hunk).left())), null), true);
        button(actions, "Open Full Diff", "git.change.full", full, true);
        JButton rollback = button(actions, "Revert Change", "git.change.revert", revert, canRevert);
        rollback.setToolTipText(canRevert ? "Restore this block from HEAD · one Undo step · autosaves · Git index unchanged" : "Revert unavailable while synchronization, repository state or a Git conflict needs attention");
        TextRange old = result.hunks().get(hunk).left();
        previousLines = old.endLine() - old.firstLine();
        preview = new DiffSidePreview(result, hunk, Side.LEFT, editor.settings(), theme, report);
        currentHighlights = new DiffHighlights(editor, false); currentHighlights.showHunk(result, hunk, Side.RIGHT, 0);
        JPanel toolbar = new JPanel(new BorderLayout()); toolbar.add(bar, BorderLayout.NORTH); toolbar.add(actions, BorderLayout.CENTER);
        popup.add(toolbar, BorderLayout.NORTH);
        if (!old.empty()) popup.add(preview, BorderLayout.CENTER);
        popup.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "close-preview");
        popup.getActionMap().put("close-preview", new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { close(); editor.focusText(); } });
        popup.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) { }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) { release(); }
            public void popupMenuCanceled(PopupMenuEvent event) { release(); }
        });
    }
    private JButton button(JPanel panel, String label, String name, Runnable action, boolean enabled) {
        JButton button = new JButton(label); button.setName(name); button.setEnabled(enabled); button.addActionListener(event -> action.run()); panel.add(button); return button;
    }
    void show(TextEditor editor, TextRange current) {
        UiTypography.apply(popup, theme);
        GraphicsConfiguration screen = editor.getGraphicsConfiguration(); Rectangle bounds = new Rectangle(screen.getBounds()); Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        bounds.x += insets.left; bounds.y += insets.top; bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
        int width = Math.min(Math.min(760, Math.max(240, popup.getPreferredSize().width)), bounds.width - 24);
        popup.setPopupSize(width, 100); popup.doLayout();
        int toolbarHeight = popup.getComponent(0).getPreferredSize().height + 2;
        int rowHeight = editor.textComponent().getFontMetrics(editor.textComponent().getFont()).getHeight();
        int height = Math.min(380, toolbarHeight + (previousLines == 0 ? 0 : 16 + Math.min(previousLines, 12) * rowHeight));
        Rectangle first, last;
        try {
            Rectangle2D start = editor.textComponent().modelToView2D(current.startOffset());
            Rectangle2D end = editor.textComponent().modelToView2D(Math.max(current.startOffset(), current.endOffset() - 1));
            if (start == null || end == null) { close(); return; }
            first = start.getBounds(); last = end.getBounds();
        } catch (BadLocationException error) { close(); return; }
        Point top = new Point(first.x, first.y); SwingUtilities.convertPointToScreen(top, editor.textComponent());
        Point bottom = new Point(last.x, last.y + last.height); SwingUtilities.convertPointToScreen(bottom, editor.textComponent());
        int below = bounds.y + bounds.height - bottom.y - 8;
        int above = top.y - bounds.y - 8;
        // Prefer below the entire current block. Use the space above when the screen edge prevents that.
        int y;
        if (below >= height) y = bottom.y + 4;
        else if (above >= height) y = top.y - height - 4;
        else if (below >= toolbarHeight + rowHeight) { height = below; y = bottom.y + 4; }
        else if (above >= toolbarHeight + rowHeight) { height = above; y = top.y - height - 4; }
        else {
            // Very tall blocks: keep the inspected source row visible and bound the scrollable preview.
            try {
                int end = Math.max(current.startOffset(), current.endOffset() - 1);
                int offset = Math.clamp(editor.textComponent().getCaretPosition(), current.startOffset(), end);
                Rectangle visible = editor.textComponent().getVisibleRect();
                Rectangle2D anchor = editor.textComponent().modelToView2D(offset);
                if (anchor == null || !anchor.intersects(visible)) {
                    offset = Math.clamp(editor.textComponent().viewToModel2D(visible.getLocation()), current.startOffset(), end);
                    anchor = editor.textComponent().modelToView2D(offset);
                }
                if (anchor == null) { close(); return; }
                Point caret = new Point((int)anchor.getX(), (int)anchor.getMaxY()); SwingUtilities.convertPointToScreen(caret, editor.textComponent());
                height = Math.min(height, Math.max(toolbarHeight + rowHeight, bounds.height / 2));
                y = caret.y + 4;
                if (y + height > bounds.y + bounds.height) y = caret.y - (int)anchor.getHeight() - height - 4;
            } catch (BadLocationException error) { close(); return; }
        }
        Point point = new Point(Math.clamp(top.x, bounds.x, bounds.x + bounds.width - width),
                Math.clamp(y, bounds.y, bounds.y + bounds.height - height));
        SwingUtilities.convertPointFromScreen(point, editor); popup.setPopupSize(width, height); popup.show(editor, point.x, point.y);
    }
    private void release() {
        if (closed) return;
        closed = true; currentHighlights.close(); preview.close();
    }
    @Override public void close() {
        if (closed) return;
        popup.setVisible(false); release();
    }
}
