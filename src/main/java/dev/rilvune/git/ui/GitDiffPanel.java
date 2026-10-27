package dev.rilvune.git.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.git.application.GitWorkspaceService.Comparison;
import dev.rilvune.git.domain.*;
import dev.rilvune.diff.application.DiffController;
import dev.rilvune.diff.domain.*;
import dev.rilvune.diff.ui.DiffView;
import dev.rilvune.platform.text.ui.*;
import dev.rilvune.platform.theme.EditorTheme;
import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Git owns replacement snapshots only; no document sessions, mutation capabilities or temp files. */
final class GitDiffPanel extends JPanel implements AutoCloseable {
    private final ReadOnlyTextModel leftModel = new ReadOnlyTextModel("");
    private final ReadOnlyTextModel rightModel = new ReadOnlyTextModel("");
    private final TextEditor left;
    private final TextEditor right;
    private final DiffView view;
    private final DiffController controller;
    private final JLabel metadata = new JLabel("Select a file to inspect · read only");
    private Comparison comparison;
    private String identity = "";
    private String sourceMetadata = "";
    private long revision;
    private FileHistoryQuery.Lines focusBefore, focusAfter;
    private dev.rilvune.platform.text.ui.syntax.SyntaxSupport syntax = dev.rilvune.platform.text.ui.syntax.SyntaxSupport.none();
    private dev.rilvune.platform.text.domain.syntax.SyntaxSource leftSource = dev.rilvune.platform.text.domain.syntax.SyntaxSource.derived();
    private dev.rilvune.platform.text.domain.syntax.SyntaxSource rightSource = dev.rilvune.platform.text.domain.syntax.SyntaxSource.derived();
    void syntaxSupport(dev.rilvune.platform.text.ui.syntax.SyntaxSupport support) {
        syntax = java.util.Objects.requireNonNull(support); left.installSyntax(support, leftSource); right.installSyntax(support, rightSource);
    }
    private static dev.rilvune.platform.text.domain.syntax.SyntaxSource source(String path) {
        return dev.rilvune.platform.text.domain.syntax.SyntaxSource.file(path.substring(path.lastIndexOf('/') + 1));
    }
    GitDiffPanel(TextEditorSettings settings, EditorTheme theme, DiffEngine engine, ExecutorService workers, Consumer<String> report) {
        super(new BorderLayout()); setMinimumSize(new Dimension(250, 140));
        left = new TextEditor(leftModel.document(), Optional.empty(), settings, theme, report, TextEditor::standardMenu);
        right = new TextEditor(rightModel.document(), Optional.empty(), settings, theme, report, TextEditor::standardMenu);
        left.textComponent().setName("git.diff.left"); right.textComponent().setName("git.diff.right");
        left.textComponent().getAccessibleContext().setAccessibleName("Git before · read only"); right.textComponent().getAccessibleContext().setAccessibleName("Git after · read only");
        view = new DiffView(new DiffView.Source(left, left), new DiffView.Source(right, right), theme, Optional.empty(), Optional.empty(), ignored -> compare(), report);
        view.compactContext(true);
        controller = new DiffController(engine, workers, SwingUtilities::invokeLater, result -> { focusSelection(); view.showResult(result); }, view::showError);
        metadata.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10)); add(metadata, BorderLayout.NORTH); add(view);
    }
    void show(Comparison comparison, GitSource first, GitSource second, GitChange path, String editorState, Consumer<GitChange> open) {
        focusBefore = null; focusAfter = null;
        boolean same = identity.equals(path.identity()); Comparison previous = this.comparison; identity = path.identity(); this.comparison = comparison; revision++;
        leftSource = source(path.oldPath().isEmpty() ? path.path() : path.oldPath()); rightSource = source(path.path());
        if (!same) { left.installSyntax(syntax, leftSource); right.installSyntax(syntax, rightSource); }
        else if (!comparison.equals(previous)) { left.replaceSyntaxSource(leftSource); right.replaceSyntaxSource(rightSource); }
        Runnable replace = () -> {
            if (!left.textComponent().getText().equals(comparison.left().text())) leftModel.replaceSnapshot(comparison.left().text());
            if (!right.textComponent().getText().equals(comparison.right().text())) rightModel.replaceSnapshot(comparison.right().text());
        };
        if (same) view.preserveViewState(replace); else { replace.run(); left.textComponent().setCaretPosition(0); right.textComponent().setCaretPosition(0); }
        view.headers(first.label() + " · " + GitChange.escape(path.oldPath()), comparison.left().metadata(), second.label() + " · " + GitChange.escape(path.path()), comparison.right().metadata());
        String notes = comparison.left().metadata() + "  →  " + comparison.right().metadata();
        if (comparison.left().text().equals(comparison.right().text()) && !comparison.left().metadata().equals(comparison.right().metadata())) notes += " · Text is identical; source format/mode differs";
        if (!comparison.left().explanation().isEmpty()) notes += " · " + comparison.left().explanation();
        if (!comparison.right().explanation().isEmpty()) notes += " · " + comparison.right().explanation();
        sourceMetadata = notes; editorState(editorState);
        left.setMenuFactory(editor -> menu(editor, Side.LEFT, path, open)); right.setMenuFactory(editor -> menu(editor, Side.RIGHT, path, open));
        if (!same || !comparison.equals(previous) || view.result().isEmpty()) compare();
    }
    void focusLines(FileHistoryQuery.Lines before, FileHistoryQuery.Lines after) {
        focusBefore = before; focusAfter = after; focusSelection();
    }
    private void focusSelection() { focus(left, focusBefore); focus(right, focusAfter); }
    private void focus(TextEditor editor, FileHistoryQuery.Lines lines) {
        if (lines == null) return;
        JTextArea text = editor.textComponent();
        try {
            int first = Math.min(lines.first(), text.getLineCount() - 1);
            int start = text.getLineStartOffset(first);
            int end = lines.end() < text.getLineCount() ? text.getLineStartOffset(lines.end()) : text.getDocument().getLength();
            text.setCaretPosition(end); text.moveCaretPosition(start); text.getCaret().setSelectionVisible(true);
        } catch (javax.swing.text.BadLocationException failure) { throw new IllegalStateException(failure); }
    }
    private JPopupMenu menu(TextEditor editor, Side side, GitChange path, Consumer<GitChange> open) {
        JPopupMenu menu = TextEditor.standardMenu(editor); view.addContextActions(menu, side, editor.contextOffset()); menu.addSeparator(); JMenuItem item = new JMenuItem("Open working file in editor"); item.addActionListener(event -> open.accept(path)); menu.add(item); return menu;
    }
    void clear(String message) {
        controller.invalidate(); comparison = null; identity = ""; focusBefore = null; focusAfter = null;
        leftSource = dev.rilvune.platform.text.domain.syntax.SyntaxSource.derived(); rightSource = leftSource;
        left.installSyntax(syntax, leftSource); right.installSyntax(syntax, rightSource);
        leftModel.replaceSnapshot(""); rightModel.replaceSnapshot(""); metadata.setText(message); metadata.setToolTipText(message);
        view.headers("Before · read only", "", "After · read only", ""); view.showError(message);
    }
    void editorState(String state) { String description = sourceMetadata + (state.isEmpty() ? "" : " · " + state); metadata.setText(description); metadata.setToolTipText(description); }
    void updating() { metadata.setText("Updating… · previous snapshot remains visible"); }
    private void compare() {
        if (comparison == null) return;
        controller.invalidate(); view.invalidateResult();
        if (!comparison.left().comparable() || !comparison.right().comparable()) { view.showError("Text comparison unavailable · see source metadata above"); return; }
        controller.compare(new TextSnapshot("git:left:" + identity, revision, comparison.left().text()), new TextSnapshot("git:right:" + identity, revision, comparison.right().text()), view.options());
    }
    void restore(boolean unified, boolean ignore) { view.restoreOptions(unified, ignore); }
    boolean unified() { return view.unifiedMode(); } boolean ignoreWhitespace() { return view.options().ignoreWhitespace(); }
    void preserveViewState(Runnable update) { view.preserveViewState(update); }
    void applySettings(TextEditorSettings settings, EditorTheme theme) { left.applySettings(settings, theme); right.applySettings(settings, theme); view.applyTheme(theme); UiTypography.interfaceFontAtSize(metadata, theme, Font.PLAIN, Math.max(11f, theme.interfaceFont().getSize2D() - 1)); metadata.setForeground(theme.muted()); }
    @Override public void close() { controller.close(); view.close(); left.close(); right.close(); }
}
