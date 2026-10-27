package dev.rilvune.document.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.domain.SyncState;
import dev.rilvune.platform.text.ui.EditorUndo;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.settings.domain.EditorSettings;

import javax.swing.*;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Document-specific status and resolution UI surrounding the reusable text viewport. */
public final class EditorView extends JPanel implements AutoCloseable {
    private final DocumentBinding binding;
    private final DocumentSession session;
    private final TextEditor textEditor;
    private final Consumer<DocumentSnapshot> changed;
    private final Consumer<EditorView> chooseCopy;
    private final Consumer<String> report;
    private final Consumer<DocumentSnapshot> inspectConflict;
    private final JPanel warning = new JPanel(new BorderLayout(8, 6));
    private final JLabel warningText = new JLabel();
    private final JPanel warningActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JLabel position = new JLabel();
    private final JLabel synchronization = new JLabel();
    private final AutoCloseable subscription;
    private final CaretListener positionListener;
    private EditorSettings settings;
    private EditorTheme theme;
    private DocumentSnapshot displayed;
    private dev.rilvune.platform.text.ui.TextEditorExtension extension;
    private boolean closed;

    public EditorView(DocumentBinding binding, EditorSettings settings, EditorTheme theme,
                      Consumer<DocumentSnapshot> changed, Consumer<EditorView> chooseCopy, Consumer<String> report,
                      Consumer<DocumentSnapshot> inspectConflict) {
        super(new BorderLayout());
        this.binding = binding;
        this.session = binding.session();
        this.settings = settings; this.theme = theme;
        this.changed = changed; this.chooseCopy = chooseCopy; this.report = report;
        this.inspectConflict = inspectConflict;
        displayed = session.snapshot();
        textEditor = new TextEditor(binding.model(), Optional.of(binding.commands()), textSettings(settings),
                theme, report, TextEditor::standardMenu, binding.searchExecutor());
        warning.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 12));
        warning.add(warningText, BorderLayout.NORTH); warning.add(warningActions, BorderLayout.CENTER);
        add(warning, BorderLayout.NORTH); add(textEditor, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        footer.add(synchronization, BorderLayout.WEST); footer.add(position, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        positionListener = event -> updatePosition();
        textEditor.textComponent().addCaretListener(positionListener);
        subscription = binding.attach(textEditor, this::receive);
        applySettings(settings, theme);
    }

    public dev.rilvune.platform.text.ui.LiveEditorDocument documentAccess() { return new LiveDocumentAccess(binding); }
    public void installExtension(dev.rilvune.platform.text.ui.TextEditorExtension value) {
        if (extension != null) throw new IllegalStateException("Editor extension already installed");
        extension = value; extension.applyTheme(theme);
    }
    public void active(boolean value) { if (extension != null) extension.active(value); }

    public DocumentSession session() { return session; }
    public TextEditor textEditor() { return textEditor; }
    public void installSyntax(dev.rilvune.platform.text.ui.syntax.SyntaxSupport support) {
        textEditor.installSyntax(support, syntaxSource(displayed));
    }
    private static dev.rilvune.platform.text.domain.syntax.SyntaxSource syntaxSource(DocumentSnapshot snapshot) {
        return snapshot.path() == null ? dev.rilvune.platform.text.domain.syntax.SyntaxSource.draft()
                : dev.rilvune.platform.text.domain.syntax.SyntaxSource.file(snapshot.path().getFileName().toString());
    }
    public JTextArea textComponent() { return textEditor.textComponent(); }
    public EditorUndo undoHistory() { return binding.undo(); }
    public void setEditingEnabled(boolean enabled) { binding.setEditingEnabled(enabled); }
    public void focusText() { textEditor.focusText(); }
    public void caret(int offset) { textEditor.caret(offset); }
    public void goToLine(int line) { textEditor.goToLine(line); }

    public void applySettings(EditorSettings value, EditorTheme palette) {
        settings = value; theme = palette;
        textEditor.applySettings(textSettings(value), palette);
        if (extension != null) extension.applyTheme(palette);
        UiTypography.interfaceFontAtSize(position, theme, Font.PLAIN, Math.max(11f, theme.interfaceFont().getSize2D() - 2));
        UiTypography.interfaceFontAtSize(synchronization, theme, Font.PLAIN, position.getFont().getSize2D()); synchronization.setForeground(theme.muted()); position.setForeground(theme.muted());
        warning.setBackground(new Color(0x302B22));
        UiTypography.apply(this, theme);
        updatePosition(); revalidate(); repaint();
    }

    private static TextEditorSettings textSettings(EditorSettings value) {
        return new TextEditorSettings(value.tabSize(), value.useSpaces(), value.softWrap(),
                value.showLineNumbers(), value.highlightCurrentLine(), value.showMinimap(), value.syntaxHighlighting());
    }

    private void receive(DocumentSnapshot snapshot) {
        if (closed) return;
        displayed = snapshot;
        textEditor.syntaxSource(syntaxSource(snapshot));
        synchronization.setText(stateLabel(snapshot)); synchronization.setToolTipText(snapshot.details());
        updateWarning(snapshot); updatePosition(); changed.accept(snapshot);
    }

    public static String stateLabel(DocumentSnapshot snapshot) {
        String label = switch (snapshot.state()) {
            case SYNCHRONIZED -> "";
            case SYNCHRONIZING -> "";
            case DRAFT -> snapshot.recoveryFailed() ? "Draft" : "Draft · saved automatically";
            case CONFLICT -> "Conflict · awaiting your decision";
            case DELETED -> "Deleted on disk · text retained in editor";
            case ERROR -> "Write failed · text retained in editor";
            case PAUSED -> "Sync paused";
        };
        return snapshot.recoveryFailed() ? (label.isEmpty() ? "Recovery write failed" : label + " · recovery write failed") : label;
    }
    private void updateWarning(DocumentSnapshot snapshot) {
        warning.setVisible(snapshot.needsAttention()); warningActions.removeAll();
        if (!snapshot.needsAttention()) return;
        warningText.setText(stateLabel(snapshot)); warningText.setToolTipText(snapshot.details());
        if (snapshot.recoveredPause()) {
            warningText.setText("Recovered document is paused · review the current file before resuming");
            warningActions.add(button("Review and resume", () -> run(session.reviewAndResume())));
        } else if (snapshot.state() == SyncState.CONFLICT) {
            warningActions.add(button("Compare", this::compare));
            warningActions.add(button("Use disk", () -> confirmResolution("Replace the editor text with the current disk version?", session::acceptDisk)));
            warningActions.add(button("Keep editor", () -> confirmResolution("Replace the disk file with the editor text?", session::overwriteDisk)));
        } else if (snapshot.state() == SyncState.DELETED) {
            warningActions.add(button("Recreate file", () -> confirmResolution("Recreate this file from the editor text?", session::overwriteDisk)));
        } else {
            warningActions.add(button("Retry", () -> run(session.flush())));
            warningActions.add(button("Details", () -> JOptionPane.showMessageDialog(this, snapshot.details(), "Synchronization", JOptionPane.ERROR_MESSAGE)));
        }
        warningActions.add(button("Save copy…", () -> chooseCopy.accept(this)));
        warningActions.add(button("Decide later", () -> { report.accept(snapshot.recoveryFailed() ? "Latest text is not checkpointed. Keep this document open and retry recovery."
                    : "This file waits for your decision. Keep it open until saving finishes."); focusText(); }));
        warning.revalidate(); warning.repaint();
    }
    private void confirmResolution(String question, java.util.function.Supplier<CompletableFuture<DocumentSnapshot>> action) {
        if (JOptionPane.showConfirmDialog(this, question, "Resolve document", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) run(action.get());
    }
    private void run(CompletableFuture<?> future) { future.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> { if (error != null) report.accept(error.getMessage()); })); }
    public void compare() {
        DocumentSnapshot snapshot = session.snapshot();
        if (snapshot.externalText() == null) { report.accept("There is no disk version to compare."); return; }
        inspectConflict.accept(snapshot);
    }
    private void updatePosition() {
        try {
            int line = textEditor.textComponent().getLineOfOffset(textEditor.textComponent().getCaretPosition());
            DocumentSnapshot snapshot = displayed;
            position.setText((line + 1) + ":" + (textEditor.textComponent().getCaretPosition() - textEditor.textComponent().getLineStartOffset(line) + 1)
                    + "    UTF-8" + (snapshot.format().byteOrderMark() ? " BOM" : "") + "    " + snapshot.format().lineEnding().name()
                    + "    " + (settings.useSpaces() ? settings.tabSize() + " spaces" : "Tab"));
        } catch (BadLocationException ignored) { }
    }
    private JButton button(String label, Runnable action) {
        JButton button = new JButton(label); button.addActionListener(event -> action.run()); return button;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (extension != null) extension.close();
        textEditor.textComponent().removeCaretListener(positionListener);
        try { subscription.close(); }
        catch (Exception exception) { report.accept(exception.getMessage()); }
        textEditor.close();
    }
}
