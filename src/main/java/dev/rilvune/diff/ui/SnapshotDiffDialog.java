package dev.rilvune.diff.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.diff.application.DiffController;
import dev.rilvune.diff.domain.DiffEngine;
import dev.rilvune.diff.domain.TextSnapshot;
import dev.rilvune.platform.text.ui.ReadOnlyTextModel;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.text.domain.syntax.SyntaxSource;
import dev.rilvune.platform.text.ui.syntax.SyntaxSupport;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Inspection of two fixed snapshots using the same diff renderer as live comparisons. */
public final class SnapshotDiffDialog extends JDialog {
    public record Source(String label, String description, TextSnapshot snapshot, SyntaxSource syntax) {
        public Source { java.util.Objects.requireNonNull(syntax); }
        /** Without verified complete-source metadata this dialog deliberately stays plain. */
        public Source(String label, String description, TextSnapshot snapshot) { this(label, description, snapshot, SyntaxSource.derived()); }
    }
    private final TextEditor left;
    private final TextEditor right;
    private final DiffView view;
    private final DiffController controller;
    private boolean closed;

    public SnapshotDiffDialog(Window owner, String title, Source first, Source second,
                              TextEditorSettings settings, EditorTheme theme, DiffEngine engine,
                              ExecutorService workers, Consumer<String> report) {
        this(owner, title, first, second, settings, theme, engine, workers, report, SyntaxSupport.none());
    }
    public SnapshotDiffDialog(Window owner, String title, Source first, Source second,
                              TextEditorSettings settings, EditorTheme theme, DiffEngine engine,
                              ExecutorService workers, Consumer<String> report, SyntaxSupport syntax) {
        super(owner, title, ModalityType.MODELESS);
        left = editor(first.snapshot(), settings, theme, report);
        right = editor(second.snapshot(), settings, theme, report);
        try { left.installSyntax(syntax, first.syntax()); right.installSyntax(syntax, second.syntax()); }
        catch (RuntimeException failure) { left.close(); right.close(); throw failure; }
        view = new DiffView(new DiffView.Source(left, left), new DiffView.Source(right, right), theme,
                Optional.empty(), Optional.empty(), ignored -> compare(first, second), report);
        view.headers(first.label(), first.description(), second.label(), second.description());
        controller = new DiffController(engine, workers, SwingUtilities::invokeLater, view::showResult, view::showError);
        JPanel content = new JPanel(new BorderLayout()); content.add(view);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close"); close.setName("diff.snapshot.close"); close.addActionListener(event -> dispose());
        actions.add(close); content.add(actions, BorderLayout.SOUTH);
        UiTypography.apply(content, theme);
        setContentPane(content); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "closeSnapshot");
        getRootPane().getActionMap().put("closeSnapshot", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { dispose(); }
        });
        setSize(1100, 720); setLocationRelativeTo(owner); compare(first, second);
    }
    private TextEditor editor(TextSnapshot snapshot, TextEditorSettings settings, EditorTheme theme, Consumer<String> report) {
        return new TextEditor(new ReadOnlyTextModel(snapshot.text()).document(), Optional.empty(), settings, theme, report, TextEditor::standardMenu);
    }
    private void compare(Source first, Source second) {
        view.invalidateResult(); controller.compare(first.snapshot(), second.snapshot(), view.options());
    }
    public void applySettings(TextEditorSettings settings, EditorTheme theme) {
        view.withoutScrollSync(() -> { left.applySettings(settings, theme); right.applySettings(settings, theme); view.applyTheme(theme); });
        UiTypography.apply(getContentPane(), theme);
    }
    public void preserveViewState(Runnable update) { view.preserveViewState(update); }
    @Override public void dispose() {
        if (!closed) { closed = true; controller.close(); view.close(); left.close(); right.close(); }
        super.dispose();
    }
}
