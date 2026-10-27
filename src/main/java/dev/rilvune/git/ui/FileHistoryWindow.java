package dev.rilvune.git.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.diff.domain.DiffEngine;
import dev.rilvune.git.application.GitWorkspaceService;
import dev.rilvune.git.domain.*;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.theme.EditorTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** One modeless contextual history, sharing Git's commit table, details, diff and request lifecycle. */
public final class FileHistoryWindow extends JDialog implements AutoCloseable {
    private record Subject(Path project, Path file, String text, FileHistoryQuery.Lines selection) { }
    private final GitWorkspaceService service;
    private final DiffEngine engine;
    private final GitHistoryPanel history;
    private final GitCommitDetails details = new GitCommitDetails();
    private final GitDiffPanel diff;
    private final Consumer<Path> openFile;
    private final JLabel scope = new JLabel(), status = new JLabel();
    private final JButton wholeFile = new JButton("Show File History");
    private final JSplitPane top, content;
    private final List<FileHistoryEntry> entries = new ArrayList<>();
    private GitWorkspaceService.FileHistoryContext context;
    private Subject subject;
    private String selected = "";
    private int retainedBytes;
    private boolean loading, hasMore, closed, positioned;

    public FileHistoryWindow(Window owner, GitWorkspaceService service, DiffEngine engine, ExecutorService workers,
                             TextEditorSettings settings, EditorTheme theme, Consumer<Path> openFile) {
        super(owner, "History", ModalityType.MODELESS);
        this.service = service; this.engine = engine; this.openFile = openFile;
        setName("git.fileHistoryWindow"); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 520)); setSize(1150, 820); setLocationRelativeTo(owner);
        history = new GitHistoryPanel(theme, this::loadMore, Optional.empty()); history.singleSelection();
        history.onSelection(commits -> { if (!commits.isEmpty()) select(commits.getFirst().id()); });
        diff = new GitDiffPanel(settings, theme, engine, workers, this::failure);
        JPanel heading = new JPanel(new BorderLayout(10, 0)); heading.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 8));
        scope.setName("git.historyScope"); scope.putClientProperty("html.disable", Boolean.TRUE); heading.add(scope);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton refresh = new JButton("Refresh"); refresh.setName("git.historyRefresh"); refresh.addActionListener(event -> refresh());
        wholeFile.setName("git.historyWholeFile"); wholeFile.addActionListener(event -> { if (subject != null) open(subject.project(), subject.file(), null, null); });
        JButton hide = new JButton("Hide"); hide.addActionListener(event -> clear());
        actions.add(wholeFile); actions.add(refresh); actions.add(hide); heading.add(actions, BorderLayout.EAST);
        top = GitUi.split(JSplitPane.HORIZONTAL_SPLIT, history, details, .68);
        content = GitUi.split(JSplitPane.VERTICAL_SPLIT, top, diff, .38);
        status.setName("git.historyStatus"); status.putClientProperty("html.disable", Boolean.TRUE);
        status.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(heading, BorderLayout.NORTH); add(content); add(status, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { clear(); } });
        getRootPane().registerKeyboardAction(event -> clear(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        applySettings(settings, theme);
    }
    public void open(Path project, Path file, String text, FileHistoryQuery.Lines selection) {
        if (closed) return;
        subject = new Subject(project, file, selection == null ? null : text, selection);
        String name = GitChange.escape(file.getFileName().toString());
        setTitle("History — " + name);
        scope.setText((selection == null ? "File: " : "Selection: ") + name
                + (selection == null ? "" : " · lines " + (selection.first() + 1) + "–" + selection.end()) + " · read only");
        scope.setToolTipText(GitChange.escape(file.toString()) + (selection == null ? "" : " · Committed line history; selection is captured when opened"));
        wholeFile.setVisible(selection != null);
        setVisible(true);
        if (!positioned) { positioned = true; top.setDividerLocation(.68); content.setDividerLocation(.38); }
        toFront(); refresh();
    }
    public void syntaxSupport(dev.rilvune.platform.text.ui.syntax.SyntaxSupport support) { diff.syntaxSupport(support); }
    private void refresh() {
        if (closed || subject == null) return;
        entries.clear(); context = null; selected = ""; retainedBytes = 0; loading = true; hasMore = false;
        history.data(List.of(), List.of(), true, false, Set.of()); history.loading();
        details.show(""); diff.clear("Loading history…"); status("Loading history…");
        Subject captured = subject;
        service.fileHistoryContext(captured.project(), captured.file(), captured.text(), captured.selection(), engine, loaded -> {
            context = loaded; loading = false; hasMore = true; loadMore();
        }, this::failure);
    }
    private void loadMore() {
        if (closed || !isVisible() || context == null || loading || !hasMore || entries.size() >= 20000) return;
        loading = true; history.loading();
        service.fileHistory(context.query(), entries.size(), 100, page -> {
            int cost = page.entries().stream().mapToInt(entry -> 512 + 2 * (entry.commit().subject().length()
                    + entry.commit().author().length() + entry.change().path().length() + entry.change().oldPath().length())).sum();
            if (retainedBytes + cost > 8 * 1024 * 1024) { loading = false; hasMore = false; history.limit("History memory limit · narrow the scope"); status("History reached its 8 MiB display limit"); return; }
            entries.addAll(page.entries()); retainedBytes += cost; hasMore = page.hasMore(); loading = false;
            history.data(entries.stream().map(FileHistoryEntry::commit).toList(), List.of(), true, hasMore && entries.size() < 20000,
                    selected.isEmpty() ? Set.of() : Set.of(selected));
            status(entries.size() + " commits · HEAD " + context.query().startCommit().substring(0, 10)
                    + (context.query().selection() == null ? " · follows file renames" : " · selected lines"));
            if (entries.isEmpty()) { diff.clear("No committed history for this file"); details.show("This file has no history reachable from the current HEAD."); }
            else if (selected.isEmpty()) history.selectFirst();
            if (entries.size() >= 20000) history.limit("20,000 row limit");
        }, this::failure);
    }
    private void select(String id) {
        FileHistoryEntry entry = entries.stream().filter(value -> value.commit().id().equals(id)).findFirst().orElseThrow();
        selected = id; diff.updating(); details.show("Loading commit…");
        service.commit(id, details::show, this::failure);
        GitSource before = entry.commit().parents().isEmpty() ? GitSource.empty() : GitSource.tree(entry.commit().parents().getFirst(), "Parent");
        GitSource after = GitSource.tree(id, entry.commit().shortId());
        Path working = subject.file();
        service.comparison(before, after, entry.change(), loaded -> {
            diff.show(loaded, before, after, entry.change(), "", ignored -> openFile.accept(working));
            diff.focusLines(entry.before(), entry.after());
        }, this::failure);
    }
    private void failure(String message) {
        loading = false; history.limit("Unavailable · Refresh to retry"); status(message);
        diff.clear("History unavailable · " + message);
    }
    private void status(String message) { status.setText(message); status.setToolTipText(message); }
    public void clear() {
        service.pause(); setVisible(false); subject = null; context = null; selected = "";
        entries.clear(); retainedBytes = 0; loading = false; hasMore = false;
        history.data(List.of(), List.of(), true, false, Set.of()); details.show(""); diff.clear("Select a file to inspect history");
    }
    public void applySettings(TextEditorSettings settings, EditorTheme theme) {
        diff.preserveViewState(() -> {
            SwingUtilities.updateComponentTreeUI(this); history.applyTheme(theme); details.applyTheme(theme); diff.applySettings(settings, theme);
            UiTypography.apply(getContentPane(), theme); status.setForeground(theme.muted());
        });
    }
    @Override public void close() { if (closed) return; clear(); closed = true; service.close(); diff.close(); dispose(); }
}
