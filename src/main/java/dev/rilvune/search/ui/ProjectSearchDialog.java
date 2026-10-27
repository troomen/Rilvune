package dev.rilvune.search.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.search.application.ProjectReplacement;
import dev.rilvune.search.application.ReplacementProblem;
import dev.rilvune.search.application.ReplacementResult;
import dev.rilvune.search.application.ProjectSearch;
import dev.rilvune.workspace.domain.Workspace;
import dev.rilvune.platform.theme.EditorTheme;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Find and replace share one result list and a file preview below, following the editor reference. */
public final class ProjectSearchDialog extends JDialog {
    private final EditorTheme theme;
    private final Workspace workspace;
    private final ProjectSearch search;
    private final ProjectReplacement replacements;
    private final Executor io;
    private final boolean names;
    private final boolean replaceMode;
    private final BiConsumer<Path, Integer> openResult;
    private final JTextField query = new JTextField();
    private final JTextField replacement = new JTextField();
    private final JCheckBox matchCase = new JCheckBox("Aa");
    private final DefaultListModel<ProjectSearch.Hit> model = new DefaultListModel<>();
    private final JList<ProjectSearch.Hit> results = new JList<>(model);
    private final JLabel status = new JLabel(" ");
    private final JLabel previewPath = new JLabel("Select a match to preview its file");
    private final JTextArea preview = new JTextArea();
    private final JTextArea after = new JTextArea();
    private final JButton open = new JButton("Open");
    private final JButton replace = new JButton("Replace");
    private final JButton replaceAll = new JButton("Replace all…");
    private final JButton cancel = new JButton("Close");
    private final JButton retryCleanup = new JButton("Retry cleanup");
    private final JButton details = new JButton("Details…");
    private ProjectReplacement.Plan replacementPlan;
    private String problemDetails = "";
    private String replacementStatus;
    private final Timer debounce;
    private volatile long generation;
    private volatile long afterVersion;
    private boolean afterRunning, afterPending;
    private long previewGeneration;
    private boolean limited;
    private boolean busy;
    private volatile boolean closed;
    private String previewText = "";

    public ProjectSearchDialog(Frame owner, EditorTheme theme, Workspace workspace, ProjectSearch search,
                               ProjectReplacement replacements, Executor io, boolean fileNames, boolean replaceMode,
                               BiConsumer<Path, Integer> openResult, Function<Path, Action> comparisonActions) {
        super(owner, fileNames ? "Go to File" : replaceMode ? "Replace in Files" : "Find in Files", ModalityType.APPLICATION_MODAL);
        this.theme = theme; this.workspace = workspace; this.search = search; this.replacements = replacements;
        this.io = io; this.names = fileNames; this.replaceMode = replaceMode; this.openResult = openResult;
        setName(fileNames ? "goToFileDialog" : replaceMode ? "replaceInFilesDialog" : "findInFilesDialog");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setLayout(new BorderLayout(0, 10));
        JPanel header = new JPanel(new BorderLayout(10, 10)); header.setBorder(BorderFactory.createEmptyBorder(14, 18, 0, 18));
        JLabel location = new JLabel(workspace.root().toString()); location.setForeground(theme.muted()); header.add(location, BorderLayout.NORTH);
        JPanel inputs = new JPanel(new GridLayout(0, 1, 0, 8));
        JPanel findRow = new JPanel(new BorderLayout(8, 0)); query.setName("projectSearchQuery"); query.putClientProperty("JTextField.placeholderText", fileNames ? "File name or path" : "Find text");
        findRow.add(query, BorderLayout.CENTER); matchCase.setToolTipText("Match case"); findRow.add(matchCase, BorderLayout.EAST); inputs.add(findRow);
        if (replaceMode) { replacement.setName("projectReplacement"); replacement.putClientProperty("JTextField.placeholderText", "Replace with"); inputs.add(replacement); }
        header.add(inputs, BorderLayout.CENTER); add(header, BorderLayout.NORTH);
        results.setName("projectSearchResults"); results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); results.setCellRenderer(new HitRenderer());
        results.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadPreview(); });
        results.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int index = results.locationToIndex(event.getPoint());
                if (index < 0 || !results.getCellBounds(index, index).contains(event.getPoint())) return;
                results.setSelectedIndex(index);
                JPopupMenu menu = new JPopupMenu(); JMenuItem item = new JMenuItem(comparisonActions.apply(results.getSelectedValue().path()));
                item.addActionListener(action -> dispose());
                menu.add(item); menu.show(results, event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
        });
        JScrollPane listScroll = new JScrollPane(results); listScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel previewPanel = new JPanel(new BorderLayout()); previewPath.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12)); previewPath.setForeground(theme.muted()); previewPanel.add(previewPath, BorderLayout.NORTH);
        for (JTextArea area : List.of(preview, after)) { area.setEditable(false); UiTypography.editorFont(area, theme); area.setMargin(new Insets(10, 14, 18, 14)); area.setTabSize(4); }
        preview.setName("projectSearchPreview"); after.setName("projectReplacementPreview");
        if (replaceMode) { JTabbedPane pages = new JTabbedPane(); pages.addTab("Current file", new JScrollPane(preview)); pages.addTab("After replacement", new JScrollPane(after)); previewPanel.add(pages); }
        else previewPanel.add(new JScrollPane(preview));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, previewPanel); split.setResizeWeight(0.37); split.setDividerSize(5); split.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, theme.border())); add(split);
        JPanel footer = new JPanel(new BorderLayout(10, 0)); footer.setBorder(BorderFactory.createEmptyBorder(0, 18, 12, 18)); status.setForeground(theme.muted()); footer.add(status);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        if (replaceMode) { replace.addActionListener(event -> replace(false)); replaceAll.addActionListener(event -> replace(true)); actions.add(replace); actions.add(replaceAll); }
        retryCleanup.setName("replacementRetryCleanup");
        retryCleanup.addActionListener(event -> retryCleanup());
        details.addActionListener(event -> {
            JTextArea text = new JTextArea(problemDetails, 14, 80); text.setEditable(false); text.setCaretPosition(0);
            JOptionPane.showMessageDialog(this, new JScrollPane(text), "Replacement details", JOptionPane.INFORMATION_MESSAGE);
        });
        retryCleanup.setVisible(replacements != null && replacements.cleanupPending()); details.setVisible(false);
        actions.add(details); actions.add(retryCleanup);
        open.addActionListener(event -> openSelected()); cancel.addActionListener(event -> dispose()); actions.add(open); actions.add(cancel); footer.add(actions, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
        UiTypography.apply(getContentPane(), theme);
        debounce = new Timer(180, event -> startSearch()); debounce.setRepeats(false);
        query.getDocument().addDocumentListener(listener(this::queryChanged)); matchCase.addActionListener(event -> queryChanged());
        replacement.getDocument().addDocumentListener(listener(this::updateAfter)); query.addActionListener(event -> openSelected());
        bind(query, "DOWN", "next", () -> moveSelection(1)); bind(query, "UP", "previous", () -> moveSelection(-1)); bind(results, "ENTER", "open", this::openSelected);
        results.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) openSelected(); } });
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() { @Override public void windowOpened(WindowEvent event) { split.setDividerLocation(0.36); query.requestFocusInWindow(); } });
        setMinimumSize(new Dimension(700, 480)); setSize(Math.min(1100, Math.max(700, owner.getWidth() - 80)), Math.min(800, Math.max(480, owner.getHeight() - 70))); setLocationRelativeTo(owner); queryChanged();
    }
    private void queryChanged() {
        if (closed || busy) return;
        replacementStatus = null;
        generation++; previewGeneration++; afterVersion++; afterPending = false; model.clear(); previewText = ""; preview.setText(""); after.setText(""); debounce.stop(); updateButtons();
        status.setText(query.getText().isEmpty() ? "Type to search · ↑ ↓ to select · Enter to open" : "Searching…");
        if (!query.getText().isEmpty()) debounce.restart();
    }
    private void startSearch() {
        long request = generation; String text = query.getText(); boolean sensitive = matchCase.isSelected();
        CompletableFuture.supplyAsync(() -> {
            try { return search.search(workspace, text, names, sensitive, () -> closed || request != generation); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, io).whenCompleteAsync((result, failure) -> {
            if (closed || request != generation) return;
            if (failure != null) { showFailure(failure); return; }
            limited = result.limited(); model.addAll(result.hits());
            if (!model.isEmpty()) results.setSelectedIndex(0);
            if (replacementStatus == null) status.setText(result.hits().size() + (names ? " files" : " matches") + (limited ? " · Refine query (limit reached)" : "") + (result.skipped() > 0 ? " · " + result.skipped() + " skipped" : ""));
            updateButtons();
        }, SwingUtilities::invokeLater);
    }
    private void loadPreview() {
        afterVersion++; afterPending = false;
        updateButtons(); ProjectSearch.Hit hit = results.getSelectedValue(); long request = ++previewGeneration;
        if (hit == null) return;
        previewPath.setText(workspace.root().relativize(hit.path()) + (hit.line() > 0 ? "  ·  line " + hit.line() : ""));
        CompletableFuture.supplyAsync(() -> {
            try { return search.readPreview(workspace, hit.path()); } catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, io).whenCompleteAsync((text, failure) -> {
            if (closed || request != previewGeneration) return;
            if (failure != null) { preview.setText("Preview unavailable: " + message(failure)); return; }
            previewText = text; preview.setText(text); preview.getHighlighter().removeAllHighlights();
            if (!names && hit.offset() + query.getText().length() <= text.length()) try {
                preview.getHighlighter().addHighlight(hit.offset(), hit.offset() + query.getText().length(), new DefaultHighlighter.DefaultHighlightPainter(theme.selection()));
            } catch (javax.swing.text.BadLocationException ignored) { }
            preview.setCaretPosition(Math.min(text.length(), hit.offset())); updateAfter();
        }, SwingUtilities::invokeLater);
    }
    private void updateAfter() {
        if (!replaceMode || closed) return;
        afterVersion++; afterPending = true;
        calculateAfter();
    }
    private void calculateAfter() {
        if (afterRunning || !afterPending || closed) return;
        afterPending = false; afterRunning = true;
        long request = afterVersion;
        String source = previewText, inserted = replacement.getText();
        var needle = new dev.rilvune.platform.text.domain.LiteralText.Query(query.getText(), matchCase.isSelected(), false);
        ProjectSearch.Hit hit = results.getSelectedValue();
        CompletableFuture<String> calculation;
        try {
            calculation = CompletableFuture.supplyAsync(() -> dev.rilvune.platform.text.domain.LiteralText.replace(source,
                    needle, inserted, 100000, dev.rilvune.platform.text.domain.LiteralText.MAX_RESULT_CHARACTERS,
                    () -> closed || request != afterVersion).text(), io);
        } catch (RuntimeException rejected) { calculation = CompletableFuture.failedFuture(rejected); }
        calculation.whenCompleteAsync((text, failure) -> {
            afterRunning = false;
            if (!closed && request == afterVersion) {
                after.setText(failure == null ? text : "Preview unavailable: " + message(failure));
                after.setCaretPosition(failure != null || hit == null ? 0 : Math.min(after.getDocument().getLength(), hit.offset()));
            }
            calculateAfter();
        }, SwingUtilities::invokeLater);
    }
    private void replace(boolean all) {
        if (busy || replacements == null || results.getSelectedValue() == null || all && limited) return;
        List<ProjectSearch.Hit> hits = all ? java.util.Collections.list(model.elements()) : List.of(results.getSelectedValue());
        setBusy(true); status.setText("Preparing replacement…");
        replacements.prepare(hits, query.getText(), replacement.getText(), matchCase.isSelected(), !all).whenCompleteAsync((plan, failure) -> {
            if (closed) { if (plan != null) replacements.release(plan); return; }
            if (failure != null) { finishReplacement(failure, null); return; }
            replacementPlan = plan;
            if (!plan.ready()) { finishReplacement(new IllegalStateException("Replacement was cancelled by a project transition"), null); return; }
            boolean accepted = !all || JOptionPane.showConfirmDialog(this,
                    "Replace " + plan.matches() + " matches in " + plan.changedFiles() + " files?", "Replace in files", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
            // A modal confirmation runs a nested event loop: project transitions/disposal can consume the plan.
            if (closed || !accepted || !plan.ready()) {
                replacements.release(plan).whenCompleteAsync((ignored, error) -> finishReplacement(error, null), SwingUtilities::invokeLater);
                return;
            }
            replacements.apply(plan).whenCompleteAsync((result, error) -> finishReplacement(error, result), SwingUtilities::invokeLater);
        }, SwingUtilities::invokeLater);
    }
    private void finishReplacement(Throwable failure, ReplacementResult result) {
        replacementPlan = null;
        if (closed) return;
        setBusy(false); queryChanged();
        if (failure != null) showFailure(failure);
        else if (result != null) {
            status.setText(result.summary()); status.setToolTipText(result.summary());
            problemDetails = result.details(); details.setVisible(!result.successful());
        } else {
            problemDetails = ""; details.setVisible(false); status.setText("Replacement cancelled");
        }
        replacementStatus = status.getText();
        retryCleanup.setVisible(replacements != null && replacements.cleanupPending());
        updateButtons();
    }
    private void retryCleanup() {
        if (busy || replacements == null) return;
        setBusy(true); status.setText("Retrying cleanup…");
        replacements.retryCleanup().whenCompleteAsync((ignored, failure) -> {
            finishReplacement(failure, null);
            if (!closed && failure == null) { replacementStatus = "Replacement cleanup completed"; status.setText(replacementStatus); }
        }, SwingUtilities::invokeLater);
    }
    private void setBusy(boolean value) {
        busy = value;
        query.setEnabled(!value); replacement.setEnabled(!value); matchCase.setEnabled(!value);
        results.setEnabled(!value); cancel.setEnabled(!value); retryCleanup.setEnabled(!value);
        updateButtons();
    }
    private void updateButtons() {
        boolean selected = results.getSelectedValue() != null;
        boolean ready = !busy && (replacements == null || !replacements.cleanupPending());
        open.setEnabled(selected && !busy); replace.setEnabled(selected && ready); replaceAll.setEnabled(selected && ready && !limited);
    }
    private void moveSelection(int delta) { if (!model.isEmpty()) { int index = Math.clamp(results.getSelectedIndex() + delta, 0, model.size() - 1); results.setSelectedIndex(index); results.ensureIndexIsVisible(index); } }
    private void openSelected() { ProjectSearch.Hit hit = results.getSelectedValue(); if (hit != null && !busy) { dispose(); openResult.accept(hit.path(), Math.max(1, hit.line())); } }
    private void showFailure(Throwable failure) {
        problemDetails = message(failure); details.setVisible(true);
        status.setText(problemDetails.lines().findFirst().orElse("Replacement failed")); status.setToolTipText(problemDetails);
    }
    private String message(Throwable failure) {
        Throwable cause = ReplacementProblem.unwrap(failure);
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
    private void bind(JComponent component, String shortcut, String id, Runnable callback) { component.getInputMap().put(KeyStroke.getKeyStroke(shortcut), id); component.getActionMap().put(id, new AbstractAction() { public void actionPerformed(ActionEvent event) { callback.run(); } }); }
    private DocumentListener listener(Runnable callback) { return new DocumentListener() { public void insertUpdate(DocumentEvent event) { callback.run(); } public void removeUpdate(DocumentEvent event) { callback.run(); } public void changedUpdate(DocumentEvent event) { callback.run(); } }; }
    @Override public void dispose() {
        closed = true; generation++; previewGeneration++; afterVersion++; afterPending = false; debounce.stop();
        if (replacementPlan != null) replacements.release(replacementPlan);
        replacementPlan = null;
        super.dispose();
    }
    private final class HitRenderer implements ListCellRenderer<ProjectSearch.Hit> {
        @Override public Component getListCellRendererComponent(JList<? extends ProjectSearch.Hit> list, ProjectSearch.Hit hit, int index, boolean selected, boolean focus) {
            JPanel row = new JPanel(new BorderLayout(20, 0)); row.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14)); row.setBackground(selected ? theme.selection() : theme.surface());
            JLabel text = new JLabel(names ? workspace.root().relativize(hit.path()).toString() : hit.excerpt()); UiTypography.editorFont(text, theme); text.setForeground(theme.text()); row.add(text);
            if (!names) { JLabel path = new JLabel(hit.path().getFileName() + ":" + hit.line()); path.setForeground(theme.muted()); row.add(path, BorderLayout.EAST); }
            row.setToolTipText(hit.path().toString()); return row;
        }
    }
}
