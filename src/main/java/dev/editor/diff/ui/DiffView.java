package dev.editor.diff.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.diff.domain.*;
import dev.editor.platform.text.ui.ReadOnlyTextModel;
import dev.editor.platform.text.ui.TextEditor;
import dev.editor.platform.text.ui.TextLineLayout;
import dev.editor.platform.theme.EditorTheme;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Rectangle2D;
import java.util.Optional;
import java.util.function.Consumer;

/** Shared two-source renderer. Sources, mutation capabilities and persistence belong to its host. */
public final class DiffView extends JPanel implements AutoCloseable {
    public record Source(JComponent content, TextEditor editor) { }
    public interface Transfers {
        boolean allowed(Side destination);
        void copy(DiffHunk hunk, Side from);
    }
    private final Optional<Transfers> transfers;
    private final Consumer<String> report;
    private final JToolBar toolbar = new JToolBar();
    private final JButton previous = button("↑", "Previous difference (Shift+F7)", "diff.previous", () -> navigate(-1));
    private final JButton next = button("↓", "Next difference (F7)", "diff.next", () -> navigate(1));
    private final JButton useRight = button("←", "Use right change in left file (Ctrl+Alt+Left)", "diff.useRight", () -> transferSelected(Side.RIGHT));
    private final JButton useLeft = button("→", "Use left change in right file (Ctrl+Alt+Right)", "diff.useLeft", () -> transferSelected(Side.LEFT));
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Side by side", "Unified · read only"});
    private final JComboBox<String> whitespace = new JComboBox<>(new String[]{"Do not ignore spaces", "Ignore spaces"});
    private final JToggleButton compact = new JToggleButton("Compact context", false);
    private final java.util.Set<Integer> expanded = new java.util.HashSet<>();
    private DiffLineLayout.Pair lineLayout;
    private final java.util.Set<Integer> expandedUnified = new java.util.HashSet<>();
    private final JToggleButton synchronize = new JToggleButton("Sync scroll", true);
    private final JToolBar.Separator transferSeparator = new JToolBar.Separator();
    private final JLabel status = new JLabel("Comparing…");
    private final JPanel contents = new JPanel(new CardLayout());
    private final JPanel sideBySide = new JPanel(new BorderLayout());
    private final JPanel unifiedPanel = new JPanel(new BorderLayout());
    private final JLabel leftHeader = new JLabel();
    private final JLabel rightHeader = new JLabel();
    private EditorTheme theme;
    private Source left;
    private Source right;
    private DiffHighlights leftHighlights;
    private DiffHighlights rightHighlights;
    private DiffGutter gutter;
    private ChangeListener leftScroll;
    private ChangeListener rightScroll;
    private FocusAdapter leftFocus;
    private FocusAdapter rightFocus;
    private CaretListener leftCaret;
    private CaretListener rightCaret;
    private TextEditor unified;
    private ReadOnlyTextModel unifiedModel;
    private DiffHighlights unifiedHighlights;
    private UnifiedText.Projection projection;
    private JSplitPane split;
    private DiffResult result;
    private Side focused = Side.LEFT;
    private int selected;
    private boolean scrolling;
    private int scrollSuspensions;
    private boolean closed;

    public DiffView(Source left, Source right, EditorTheme theme, Optional<Transfers> transfers,
                    Optional<Runnable> swap, Consumer<DiffOptions> optionsChanged, Consumer<String> report) {
        super(new BorderLayout());
        this.theme = theme; this.transfers = transfers; this.report = report;
        setName("diffView");
        toolbar.setFloatable(false); toolbar.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        toolbar.add(previous); toolbar.add(next); toolbar.addSeparator();
        mode.setName("diff.mode"); whitespace.setName("diff.whitespace");
        mode.setMaximumSize(new Dimension(190, 30)); whitespace.setMaximumSize(new Dimension(195, 30));
        toolbar.add(mode); toolbar.addSeparator(); toolbar.add(whitespace); toolbar.addSeparator();
        synchronize.setFocusable(false); synchronize.setToolTipText("Keep corresponding lines in view"); toolbar.add(synchronize); toolbar.add(compact);
        compact.setName("diff.compact"); compact.setToolTipText("Collapse unchanged regions; click a folded row or find text to expand");
        compact.addActionListener(event -> { expanded.clear(); expandedUnified.clear(); applyLineLayout(); applyUnifiedLayout(); });
        if (transfers.isPresent()) { toolbar.add(transferSeparator); toolbar.add(useRight); toolbar.add(useLeft); }
        swap.ifPresent(action -> { toolbar.addSeparator(); toolbar.add(button("⇄", "Swap left and right files", "diff.swap", action)); });
        toolbar.add(Box.createHorizontalGlue()); toolbar.add(status);
        mode.addActionListener(event -> { showMode(); refreshActions(); });
        whitespace.addActionListener(event -> optionsChanged.accept(options()));
        synchronize.addActionListener(event -> { if (synchronize.isSelected()) sync(focused); });
        contents.add(sideBySide, "sides"); contents.add(unifiedPanel, "unified");
        add(toolbar, BorderLayout.NORTH); add(contents, BorderLayout.CENTER);
        setSources(left, right);
        bind("F7", "next-difference", () -> navigate(1)); bind("shift F7", "previous-difference", () -> navigate(-1));
        bind("ctrl alt RIGHT", "use-left-change", () -> transferSelected(Side.LEFT));
        bind("ctrl alt LEFT", "use-right-change", () -> transferSelected(Side.RIGHT));
        applyTheme(theme);
    }
    public void setSources(Source first, Source second) {
        detachSources();
        left = first; right = second;
        leftHighlights = new DiffHighlights(left.editor()); rightHighlights = new DiffHighlights(right.editor());
        gutter = new DiffGutter(left.editor(), right.editor(), theme, this::canTransfer, (hunk, from) -> transfer(hunk, from));
        JPanel leftSide = new JPanel(new BorderLayout());
        leftSide.add(column(leftHeader, left.content()), BorderLayout.CENTER); leftSide.add(gutter, BorderLayout.EAST);
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSide, column(rightHeader, right.content()));
        split.setContinuousLayout(true); split.setResizeWeight(0.5); split.setDividerSize(5); split.setBorder(BorderFactory.createEmptyBorder());
        sideBySide.removeAll(); sideBySide.add(split);
        leftScroll = event -> { sync(Side.LEFT); gutter.repaint(); };
        rightScroll = event -> { sync(Side.RIGHT); gutter.repaint(); };
        left.editor().scrollPane().getViewport().addChangeListener(leftScroll);
        right.editor().scrollPane().getViewport().addChangeListener(rightScroll);
        leftFocus = focusListener(Side.LEFT); rightFocus = focusListener(Side.RIGHT);
        left.editor().textComponent().addFocusListener(leftFocus); right.editor().textComponent().addFocusListener(rightFocus);
        leftCaret = event -> selectAt(Side.LEFT, event.getDot()); rightCaret = event -> selectAt(Side.RIGHT, event.getDot());
        left.editor().textComponent().addCaretListener(leftCaret); right.editor().textComponent().addCaretListener(rightCaret);
        SwingUtilities.invokeLater(() -> { if (!closed) split.setDividerLocation(0.5); });
        invalidateResult(); revalidate(); repaint();
    }
    public void headers(String leftName, String leftPath, String rightName, String rightPath) {
        leftHeader.setText(leftName); leftHeader.setToolTipText(leftPath);
        rightHeader.setText(rightName); rightHeader.setToolTipText(rightPath);
    }
    public Optional<DiffResult> result() { return Optional.ofNullable(result); }
    public DiffOptions options() { return new DiffOptions(whitespace.getSelectedIndex() == 1); }
    public boolean unifiedMode() { return mode.getSelectedIndex() == 1; }
    public void restoreOptions(boolean unified, boolean ignoreWhitespace) {
        whitespace.setSelectedIndex(ignoreWhitespace ? 1 : 0); mode.setSelectedIndex(unified ? 1 : 0);
    }
    public Optional<TextEditor> textTarget() {
        if (unifiedMode()) return Optional.ofNullable(unified);
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null && SwingUtilities.isDescendingFrom(owner, left.content())) focused = Side.LEFT;
        if (owner != null && SwingUtilities.isDescendingFrom(owner, right.content())) focused = Side.RIGHT;
        return Optional.of(focused == Side.LEFT ? left.editor() : right.editor());
    }
    public void focusContent() { textTarget().ifPresent(TextEditor::focusText); }
    public void focus(Side side) { focused = side; (side == Side.LEFT ? left : right).editor().focusText(); }
    public void invalidateResult() {
        result = null;
        if (left != null) left.editor().setLineLayout(TextLineLayout.empty(), ignored -> { });
        if (right != null) right.editor().setLineLayout(TextLineLayout.empty(), ignored -> { });
        status.setText("Comparing…");
        if (leftHighlights != null) leftHighlights.close(); if (rightHighlights != null) rightHighlights.close();
        if (gutter != null) gutter.show(null);
        if (unifiedHighlights != null) unifiedHighlights.close();
        refreshActions();
    }
    public void showResult(DiffResult value) {
        result = value; selected = Math.clamp(selected, 0, Math.max(0, value.hunks().size() - 1));
        applyLineLayout();
        leftHighlights.show(value, Side.LEFT); rightHighlights.show(value, Side.RIGHT); gutter.show(value);
        updateStatus(); if (unifiedMode() || unified != null) refreshUnified(); refreshActions();
    }
    public void compactContext(boolean enabled) { compact.setSelected(enabled); applyLineLayout(); applyUnifiedLayout(); }
    private void applyLineLayout() {
        if (result == null) return;
        // View object count is explicitly bounded, independently of the text diff budget.
        if (result.lines(Side.LEFT).size() + result.lines(Side.RIGHT).size() > 20000) {
            left.editor().setLineLayout(TextLineLayout.empty(), ignored -> { }); right.editor().setLineLayout(TextLineLayout.empty(), ignored -> { });
            compact.setEnabled(false); compact.setToolTipText("Context folding/alignment limited to 20,000 combined lines; source panes remain available"); return;
        }
        compact.setEnabled(true);
        lineLayout = DiffLineLayout.compute(result, compact.isSelected(), expanded);
        left.editor().setLineLayout(lineLayout.left(), fold -> expand(Side.LEFT, fold));
        right.editor().setLineLayout(lineLayout.right(), fold -> expand(Side.RIGHT, fold));
        if (gutter != null) gutter.repaint();
    }
    private void expand(Side side, TextLineLayout.Fold fold) {
        if (lineLayout == null) return;
        for (var pair : lineLayout.folds()) if ((side == Side.LEFT ? pair.left() : pair.right()).equals(fold)) { expanded.add(pair.left().firstLine()); break; }
        applyLineLayout();
    }
    public void showError(String message) { invalidateResult(); status.setText(message); status.setToolTipText(message); }
    public void refreshActions() {
        boolean changes = result != null && !result.hunks().isEmpty();
        boolean navigation = changes && (!unifiedMode() || projection != null && !projection.hunkOffsets().isEmpty());
        previous.setEnabled(navigation); next.setEnabled(navigation);
        useLeft.setEnabled(changes && canTransfer(Side.RIGHT)); useRight.setEnabled(changes && canTransfer(Side.LEFT));
        useLeft.setVisible(!unifiedMode()); useRight.setVisible(!unifiedMode()); transferSeparator.setVisible(!unifiedMode());
        synchronize.setEnabled(!unifiedMode());
        if (gutter != null) gutter.repaint();
    }
    public void applyTheme(EditorTheme value) {
        theme = value; setBackground(theme.surface()); toolbar.setBackground(theme.raised());
        status.setForeground(theme.muted()); UiTypography.interfaceFont(status, theme);
        for (JLabel header : java.util.List.of(leftHeader, rightHeader)) {
            header.setOpaque(true); header.setBackground(theme.surface()); header.setForeground(theme.text());
            UiTypography.interfaceFont(header, theme); header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border()));
            header.setPreferredSize(new Dimension(0, Math.max(34, theme.interfaceFont().getSize() + 20)));
        }
        UiTypography.apply(this, theme);
        if (unified != null) unified.applySettings(left.editor().settings(), theme);
        if (gutter != null) gutter.applyTheme(theme);
        applyLineLayout();
    }
    public void navigate(int direction) {
        if (result == null || result.hunks().isEmpty()) return;
        if (unifiedMode() && (projection == null || projection.hunkOffsets().isEmpty())) return;
        selected = Math.floorMod(selected + direction, result.hunks().size());
        if (unifiedMode()) reveal(unified, projection.hunkOffsets().get(selected));
        else {
            DiffHunk hunk = result.hunks().get(selected);
            withoutScrollSync(() -> { reveal(left.editor(), hunk.left().startOffset()); reveal(right.editor(), hunk.right().startOffset()); });
        }
        updateStatus(); refreshActions();
    }
    private void reveal(TextEditor editor, int offset) {
        try {
            Rectangle2D rectangle = editor.textComponent().modelToView2D(offset);
            if (rectangle != null) {
                Rectangle bounds = rectangle.getBounds(); bounds.grow(0, 50); editor.textComponent().scrollRectToVisible(bounds);
            }
        } catch (BadLocationException ignored) { }
    }
    private boolean canTransfer(Side destination) {
        return result != null && !unifiedMode() && transfers.map(actions -> actions.allowed(destination)).orElse(false);
    }
    public void transferSelected(Side from) {
        if (result != null && !result.hunks().isEmpty()) transfer(result.hunks().get(selected), from);
    }
    private void transfer(DiffHunk hunk, Side from) {
        if (result == null || !result.hunks().contains(hunk) || !canTransfer(from.opposite())) return;
        selected = result.hunks().indexOf(hunk);
        transfers.orElseThrow().copy(hunk, from);
    }
    public void addContextActions(JPopupMenu menu, Side side, int offset) {
        DiffResult captured = result;
        Optional<DiffHunk> at = hunkAt(side, offset);
        menu.addSeparator();
        JMenuItem previous = new JMenuItem("Previous difference"); previous.setEnabled(result != null && !result.hunks().isEmpty());
        previous.addActionListener(event -> navigate(-1)); menu.add(previous);
        JMenuItem next = new JMenuItem("Next difference"); next.setEnabled(previous.isEnabled()); next.addActionListener(event -> navigate(1)); menu.add(next);
        if (transfers.isPresent()) {
            JMenuItem copy = new JMenuItem(side == Side.LEFT ? "Use left change in right file" : "Use right change in left file");
            copy.setEnabled(at.isPresent() && canTransfer(side.opposite()));
            copy.addActionListener(event -> {
                if (result != captured) report.accept("The files changed. Review the updated comparison before applying a change.");
                else at.ifPresent(hunk -> transfer(hunk, side));
            }); menu.add(copy);
        }
    }
    private Optional<DiffHunk> hunkAt(Side side, int offset) {
        if (result == null) return Optional.empty();
        return result.hunks().stream().filter(hunk -> {
            TextRange range = result.range(hunk, side);
            return offset >= range.startOffset() && (offset < range.endOffset() || range.empty() && offset == range.startOffset());
        }).findFirst();
    }
    private void selectAt(Side side, int offset) {
        hunkAt(side, offset).ifPresent(hunk -> { selected = result.hunks().indexOf(hunk); updateStatus(); });
    }
    private void updateStatus() {
        int count = result.hunks().size(); status.setToolTipText(null);
        status.setText(count == 0 ? (result.options().ignoreWhitespace() ? "Identical · ignoring spaces" : "Contents are identical")
                : (selected + 1) + " of " + count + (count == 1 ? " difference" : " differences"));
    }
    private void sync(Side from) {
        if (closed || scrolling || scrollSuspensions > 0 || !synchronize.isSelected() || result == null || unifiedMode()) return;
        TextEditor source = from == Side.LEFT ? left.editor() : right.editor();
        TextEditor target = from == Side.LEFT ? right.editor() : left.editor();
        try {
            Point position = source.scrollPane().getViewport().getViewPosition();
            int offset = source.textComponent().viewToModel2D(new Point(0, position.y));
            int line = source.textComponent().getLineOfOffset(offset);
            int mapped = result.mapLine(from, line);
            Rectangle2D sourceLine = source.textComponent().modelToView2D(source.textComponent().getLineStartOffset(line));
            Rectangle2D targetLine = target.textComponent().modelToView2D(result.lines(from.opposite()).offset(mapped));
            if (sourceLine == null || targetLine == null) return;
            int y = Math.max(0, (int) (targetLine.getY() + position.y - sourceLine.getY()));
            JViewport viewport = target.scrollPane().getViewport();
            y = Math.min(y, Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height));
            scrolling = true;
            try { viewport.setViewPosition(new Point(viewport.getViewPosition().x, y)); }
            finally { scrolling = false; }
        } catch (BadLocationException ignored) { }
    }
    private void showMode() {
        ((CardLayout) contents.getLayout()).show(contents, unifiedMode() ? "unified" : "sides");
        if (unifiedMode() && unified == null) refreshUnified();
    }
    private void refreshUnified() {
        if (result != null) {
            projection = UnifiedText.render(result);
            if (unified == null) {
                unifiedModel = new ReadOnlyTextModel(projection.text());
                unified = new TextEditor(unifiedModel.document(), Optional.empty(), left.editor().settings(), theme, report, TextEditor::standardMenu);
                unifiedHighlights = new DiffHighlights(unified);
                unified.textComponent().getAccessibleContext().setAccessibleName("Unified comparison · read only");
                unifiedPanel.add(unified);
            } else {
                TextEditor.ViewState position = unified.captureView();
                unifiedHighlights.close(); unifiedModel.replaceSnapshot(projection.text()); unified.restoreView(position);
            }
            applyUnifiedLayout();
            unifiedHighlights.showUnified(projection);
        }
        unifiedPanel.revalidate(); unifiedPanel.repaint();
    }
    private void applyUnifiedLayout() {
        if (unified == null || projection == null) return;
        java.util.List<TextLineLayout.Fold> folds = new java.util.ArrayList<>();
        if (compact.isSelected() && unified.textComponent().getLineCount() <= 20000) {
            String[] rows = projection.text().split("\n", -1); int first = -1;
            for (int line = 0; line <= rows.length; line++) {
                boolean unchanged = line < rows.length && rows[line].startsWith(" ");
                if (unchanged && first < 0) first = line;
                if (!unchanged && first >= 0) {
                    if (line - first >= 12 && !expandedUnified.contains(first + 3)) folds.add(new TextLineLayout.Fold(first + 3, line - 3));
                    first = -1;
                }
            }
        }
        unified.setLineLayout(new TextLineLayout(java.util.Map.of(), folds), fold -> { expandedUnified.add(fold.firstLine()); applyUnifiedLayout(); });
    }
    /** Hold linked scrolling through deferred viewport restoration by both source editors. */
    public void withoutScrollSync(Runnable action) {
        scrollSuspensions++;
        try { action.run(); }
        finally { SwingUtilities.invokeLater(() -> scrollSuspensions--); }
    }
    public void preserveViewState(Runnable update) {
        var first = left.editor().captureView(); var second = right.editor().captureView();
        TextEditor projectionEditor = unified;
        var projectionPosition = projectionEditor == null ? null : projectionEditor.captureView();
        withoutScrollSync(() -> {
            try { update.run(); }
            finally {
                left.editor().restoreView(first); right.editor().restoreView(second);
                if (projectionEditor != null && projectionEditor == unified) unified.restoreView(projectionPosition);
            }
        });
    }
    private JPanel column(JLabel header, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout()); panel.setMinimumSize(new Dimension(120, 0));
        panel.add(header, BorderLayout.NORTH); panel.add(content, BorderLayout.CENTER); return panel;
    }
    private FocusAdapter focusListener(Side side) { return new FocusAdapter() { @Override public void focusGained(FocusEvent event) { focused = side; } }; }
    private JButton button(String label, String tooltip, String name, Runnable action) {
        JButton button = new JButton(label); button.setName(name); button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip); button.setFocusable(false); button.addActionListener(event -> action.run()); return button;
    }
    private void bind(String shortcut, String id, Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(shortcut), id);
        getActionMap().put(id, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
    }
    private void closeUnified() {
        if (unifiedHighlights != null) unifiedHighlights.close();
        if (unified != null) unified.close();
        unified = null; unifiedModel = null; unifiedHighlights = null; projection = null;
    }
    private void detachSources() {
        if (leftHighlights != null) leftHighlights.close(); if (rightHighlights != null) rightHighlights.close();
        if (left != null) left.editor().setLineLayout(TextLineLayout.empty(), ignored -> { });
        if (right != null) right.editor().setLineLayout(TextLineLayout.empty(), ignored -> { });
        if (gutter != null) gutter.dispose();
        if (left != null) {
            left.editor().scrollPane().getViewport().removeChangeListener(leftScroll); left.editor().textComponent().removeFocusListener(leftFocus);
            right.editor().scrollPane().getViewport().removeChangeListener(rightScroll); right.editor().textComponent().removeFocusListener(rightFocus);
            left.editor().textComponent().removeCaretListener(leftCaret); right.editor().textComponent().removeCaretListener(rightCaret);
        }
    }
    @Override public void close() { if (closed) return; closed = true; detachSources(); closeUnified(); }
}
