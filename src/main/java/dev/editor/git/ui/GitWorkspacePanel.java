package dev.editor.git.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.git.application.GitWorkspaceService;
import dev.editor.git.application.ProjectGitStatusService;
import dev.editor.git.domain.*;
import dev.editor.diff.domain.DiffEngine;
import dev.editor.platform.text.ui.*;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;

/** One workspace, independent of its window/dock host. Owns selections, not repository commands. */
public final class GitWorkspacePanel extends JPanel implements AutoCloseable {
    public void syntaxSupport(dev.editor.platform.text.ui.syntax.SyntaxSupport support) { diff.syntaxSupport(support); }
    private dev.editor.git.ui.editor.CommitNavigation pendingNavigation;
    private String navigationPath = "";
    private enum Mode { CHANGES, HISTORY }
    private final GitWorkspaceService service;
    private final ProjectGitStatusService statuses;
    private final AutoCloseable statusSubscription;
    private ProjectGitStatus latestStatus;
    private final Consumer<Path> openFile;
    private final Consumer<Path> fileHistory;
    private final Function<Path, String> editorState;
    private final GitDiffPanel diff;
    private final GitHistoryPanel history;
    private final GitFilesPanel files;
    private final JTree references = new JTree(new DefaultMutableTreeNode("References"));
    private final JScrollPane referencePane = new JScrollPane(references);
    private final GitCommitDetails commitDetails = new GitCommitDetails();
    private final JTextArea message = commitDetails.textComponent();
    private final JLabel status = new JLabel("Open a project to inspect its repository");
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Local Changes", "History"});
    private final JComboBox<String> scope = new JComboBox<>(new String[]{"All branches", "Current branch"});
    private final JComboBox<String> comparison = new JComboBox<>(new String[]{"All local changes", "Staged changes", "Unstaged changes"});
    private final JComboBox<String> parent = new JComboBox<>();
    private final JComboBox<String> conflict = new JComboBox<>(new String[]{"HEAD", "Base · stage 1", "Ours · stage 2", "Theirs · stage 3"});
    private final JTextField search = new JTextField(13), author = new JTextField(10), path = new JTextField(13);
    private final JToggleButton showReferences = new JToggleButton("Refs", true), showDetails = new JToggleButton("Details", true);
    private final JLabel filterState = new JLabel();
    private final JSplitPane detailsFiles, historyDetails, referenceHistory, vertical;
    private final JPanel detailsPane = new JPanel(new BorderLayout());
    private final JPanel browsers = new JPanel(new CardLayout()), localBrowser = new JPanel(new BorderLayout());
    private final JPanel historyFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
    private final JButton jump = new JButton("Jump to…"), pair = new JButton("Compare A → B…");
    private final javax.swing.Timer debounce;
    private Path project;
    private RepositoryLocation location;
    private RepositoryState repository;
    private List<GitCommitHeader> commits = List.of();
    private GitCommit selectedCommit;
    private HistoryQuery query;
    private GitSource first = GitSource.empty(), second = GitSource.working();
    private String selectedReference = "", historySelection = "", changesSelection = "", selectedPath = "";
    private boolean contentReady, forceLocal, active, closed, historyLoading, hasMore, changing, initialized;
    private long contentVersion;
    private long historyBytes;
    private String refsVersion = "";
    private double savedReferences = .18, savedDetails = .68;
    private double fileSplit = .42;

    public GitWorkspacePanel(GitWorkspaceService service, ProjectGitStatusService statuses, DiffEngine engine, ExecutorService workers,
                             TextEditorSettings settings, EditorTheme theme, Consumer<Path> openFile, Consumer<Path> fileHistory, Function<Path, String> editorState) {
        super(new BorderLayout()); this.service = service; this.statuses = statuses; this.openFile = openFile; this.fileHistory = fileHistory; this.editorState = editorState;
        setName("git.workspace"); status.setName("git.status"); status.putClientProperty("html.disable", Boolean.TRUE);
        history = new GitHistoryPanel(theme, this::loadMore, Optional.of(() -> mode.setSelectedIndex(0)));
        files = new GitFilesPanel(theme, this::open, this::pathHistory);
        diff = new GitDiffPanel(settings, theme, engine, workers, this::failure);
        history.onSelection(this::selectCommits); files.onSelection(this::selectFile);
        JPanel parentBar = new JPanel(new BorderLayout(6, 0)); parentBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8)); parentBar.add(new JLabel("Compare to parent"), BorderLayout.WEST); parentBar.add(parent);
        JButton inspectParent = new JButton("Inspect"); inspectParent.setToolTipText("Jump to the selected parent commit"); inspectParent.addActionListener(event -> { if (selectedCommit != null && !selectedCommit.parents().isEmpty()) service.commit(selectedCommit.parents().get(Math.max(0, parent.getSelectedIndex())), this::jumpTo, this::failure); }); parentBar.add(inspectParent, BorderLayout.EAST);
        detailsPane.add(parentBar, BorderLayout.NORTH); detailsPane.add(commitDetails);
        detailsFiles = GitUi.split(JSplitPane.VERTICAL_SPLIT, detailsPane, files, .42);
        historyDetails = GitUi.split(JSplitPane.HORIZONTAL_SPLIT, history, detailsFiles, .68);
        referenceHistory = GitUi.split(JSplitPane.HORIZONTAL_SPLIT, referencePane, historyDetails, .18);
        browsers.add(referenceHistory, "history"); browsers.add(localBrowser, "local");
        vertical = GitUi.split(JSplitPane.VERTICAL_SPLIT, browsers, diff, .51);
        add(toolbar(), BorderLayout.NORTH); add(vertical); status.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10)); add(status, BorderLayout.SOUTH);
        references.setName("git.references"); references.setRootVisible(false); references.setShowsRootHandles(true);
        references.setCellRenderer(new DefaultTreeCellRenderer() { @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) { super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus); putClientProperty("html.disable", Boolean.TRUE); Object item = ((DefaultMutableTreeNode)value).getUserObject(); if (item instanceof GitRef ref) setText(ref.label()); setLeafIcon(null); setOpenIcon(null); setClosedIcon(null); return this; } });
        references.addTreeSelectionListener(event -> {
            if (changing || repository == null) return;
            Object node = references.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode item && item.getUserObject() instanceof GitRef ref) { selectedReference = ref.objectId(); filterState.setText("Ref: " + ref.label()); mode.setSelectedIndex(1); restartHistory(); }
        });
        references.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { int row = references.getClosestRowForLocation(e.getX(), e.getY()); if (row >= 0 && references.getRowBounds(row).contains(references.getRowBounds(row).x, e.getY())) references.setSelectionRow(row); } });
        mode.setName("git.mode"); comparison.setName("git.comparison"); scope.setName("git.scope"); parent.setName("git.parent"); conflict.setName("git.conflictSource");
        mode.addActionListener(event -> { if (!changing) switchMode(); }); comparison.addActionListener(event -> { if (!changing && currentMode() == Mode.CHANGES && repository != null) localFiles(); });
        parent.addActionListener(event -> { if (!changing && currentMode() == Mode.HISTORY && selectedCommit != null) commitFiles(selectedCommit); });
        conflict.addActionListener(event -> { if (!changing && files.selection() != null) selectFile(files.selection()); });
        scope.addActionListener(event -> { if (!changing) { selectedReference = ""; changing = true; references.clearSelection(); changing = false; filterState.setText(""); restartHistory(); } });
        showReferences.addActionListener(event -> paneVisibility()); showDetails.addActionListener(event -> paneVisibility());
        debounce = new javax.swing.Timer(350, event -> restartHistory()); debounce.setRepeats(false);
        DocumentListener filters = new DocumentListener() { public void insertUpdate(DocumentEvent e) { changed(); } public void removeUpdate(DocumentEvent e) { changed(); } public void changedUpdate(DocumentEvent e) { changed(); } private void changed() { if (!changing) debounce.restart(); } };
        for (JTextField input : List.of(search, author, path)) input.getDocument().addDocumentListener(filters);
        applySettings(settings, theme); switchMode();
        statusSubscription = statuses.subscribe(this::acceptStatus);
    }
    private JComponent toolbar() {
        JPanel root = new JPanel(new BorderLayout());
        JToolBar navigation = new JToolBar(); navigation.setFloatable(false); navigation.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        comparison.setToolTipText("Tracked files only · untracked files are excluded");
        mode.setMaximumSize(new Dimension(155, 32)); comparison.setMaximumSize(new Dimension(210, 32)); scope.setMaximumSize(new Dimension(180, 32));
        navigation.add(mode); navigation.addSeparator(); navigation.add(comparison); navigation.add(scope); navigation.addSeparator(); navigation.add(showReferences); navigation.add(showDetails);
        JButton refresh = new JButton("Refresh"); refresh.setName("git.refresh"); refresh.addActionListener(event -> refresh()); navigation.add(refresh);
        jump.addActionListener(event -> { String revision = JOptionPane.showInputDialog(this, "Commit ID or local reference", "Jump to commit", JOptionPane.PLAIN_MESSAGE); if (revision != null && location != null) service.jump(revision.strip(), this::jumpTo, this::failure); }); navigation.add(jump);
        pair.setToolTipText("Select two commits; choose the explicit comparison direction"); pair.addActionListener(event -> comparePair()); navigation.add(pair);
        navigation.add(conflict); navigation.add(Box.createHorizontalGlue());
        JPanel filters = historyFilters; filters.setBorder(BorderFactory.createEmptyBorder(0, 5, 4, 5));
        search.setToolTipText("Search commit messages across the full repository history"); author.setToolTipText("Filter by author across full history"); path.setToolTipText("Literal repository-relative path, including commits outside the loaded page");
        search.setName("git.messageFilter"); author.setName("git.authorFilter"); path.setName("git.pathFilter");
        search.putClientProperty("JTextField.placeholderText", "Message contains…"); author.putClientProperty("JTextField.placeholderText", "Author contains…"); path.putClientProperty("JTextField.placeholderText", "Repository path…");
        filters.add(new JLabel("History filters")); filters.add(search); filters.add(author); filters.add(path);
        JButton clear = new JButton("Clear"); clear.addActionListener(event -> { changing = true; search.setText(""); author.setText(""); path.setText(""); selectedReference = ""; references.clearSelection(); changing = false; filterState.setText(""); restartHistory(); }); filters.add(clear); filters.add(filterState);
        conflict.setVisible(false); root.add(navigation, BorderLayout.NORTH); root.add(filters, BorderLayout.SOUTH); return root;
    }
    public void project(Path root) {
        if (Objects.equals(project, root) && initialized) return;
        pendingNavigation = null; navigationPath = "";
        project = root; initialized = true; location = null; repository = null; latestStatus = null; refsVersion = "";
        selectedReference = ""; selectedCommit = null; commits = List.of(); query = null;
        historySelection = ""; changesSelection = ""; selectedPath = ""; historyLoading = false; contentReady = false;
        service.bind(null); history.data(List.of(), List.of(), false, false, Set.of()); files.data(List.of(), ""); showMessage("");
        diff.clear(root == null ? "Open a project to inspect Git" : "Loading repository…");
        status.setText(root == null ? "No project open" : "Loading repository…");
    }
    private void acceptStatus(ProjectGitStatus snapshot) {
        if (closed || !Objects.equals(snapshot.project(), project)) return;
        latestStatus = snapshot;
        location = snapshot.location(); service.bind(location);
        if (!snapshot.available()) {
            repository = null; refsVersion = ""; files.data(List.of(), ""); history.data(List.of(), List.of(), false, false, Set.of());
            diff.clear(snapshot.message()); status.setText(snapshot.message()); return;
        }
        if (active) { displayStatus(snapshot); resolveNavigation(); }
    }
    private void displayStatus(ProjectGitStatus snapshot) {
        if (snapshot.phase() == ProjectGitStatus.Phase.STALE) { failure(snapshot.message()); return; }
        LocalGitStatus local = snapshot.local(); RepositoryState prior = repository;
        repository = new RepositoryState(local.head(), local.branch(), prior == null ? List.of() : prior.refs(), local.changes(), local.fingerprint());
        String referenceVersion = local.head() + ":" + local.referenceFingerprint();
        if (currentMode() == Mode.HISTORY && !referenceVersion.equals(refsVersion)) {
            refsVersion = referenceVersion;
            service.references(refs -> {
                if (!active || latestStatus == null || !latestStatus.available()) return;
                LocalGitStatus latest = latestStatus.local();
                repository = new RepositoryState(latest.head(), latest.branch(), refs, latest.changes(), latest.fingerprint());
                references(repository); restartHistory();
            }, error -> { refsVersion = ""; failure(error); });
        }
        boolean localChanged = forceLocal || prior == null || !prior.fingerprint().equals(local.fingerprint());
        if (currentMode() == Mode.CHANGES && localChanged) localFiles();
        if (forceLocal && currentMode() == Mode.HISTORY && !contentReady && selectedCommit != null) commitFiles(selectedCommit);
        forceLocal = false;
        if (currentMode() == Mode.HISTORY && !historyLoading && query != null && commits.isEmpty()) loadMore();
        status.setText(local.branch() + (local.head().isEmpty() ? " · no commits yet" : " · " + local.head().substring(0, 10))
                + " · " + local.changes().stream().filter(change -> !change.untracked()).count() + " tracked local paths · disk snapshots · read only · remotes show local state"
                + (snapshot.message().isEmpty() ? "" : " · " + snapshot.message()));
    }
    public void activate() {
        if (closed) return; active = true; forceLocal = true;
        if (latestStatus != null && latestStatus.available()) displayStatus(latestStatus);
        statuses.refresh(); resolveNavigation();
    }
    public void deactivate() { active = false; debounce.stop(); service.pause(); refsVersion = ""; historyLoading = false; }
    public void editorChanged(Path file) {
        if (!active || project == null || !file.startsWith(project)) return;
        GitChange selected = files.selection();
        if (currentMode() == Mode.CHANGES && selected != null && project.resolve(selected.path()).equals(file)) diff.editorState(editorState.apply(file));
    }
    public void inspectCommit(dev.editor.git.ui.editor.CommitNavigation request) {
        if (closed || !Objects.equals(project, request.repository().root())) return;
        pendingNavigation = request; resolveNavigation();
    }
    private void resolveNavigation() {
        var request = pendingNavigation;
        if (!active || request == null || location == null) return;
        pendingNavigation = null;
        if (!location.equals(request.repository())) { failure("Repository changed before commit navigation"); return; }
        service.commit(request.commitId(), commit -> { navigationPath = request.historicalPath(); jumpTo(commit); }, this::failure);
    }
    public void refresh() { statuses.refresh(); }
    private void references(RepositoryState state) {
        changing = true; DefaultMutableTreeNode root = new DefaultMutableTreeNode("References");
        if (!state.head().isEmpty()) root.add(new DefaultMutableTreeNode(new GitRef("HEAD → " + state.branch(), state.head())));
        Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
        for (GitRef ref : state.refs()) { DefaultMutableTreeNode group = groups.computeIfAbsent(ref.group(), name -> { DefaultMutableTreeNode node = new DefaultMutableTreeNode(name); root.add(node); return node; }); group.add(new DefaultMutableTreeNode(ref)); }
        references.setModel(new DefaultTreeModel(root)); for (int row = 0; row < references.getRowCount(); row++) references.expandRow(row);
        changing = false;
    }
    private void showMessage(String text) {
        commitDetails.show(text);
    }
    private Mode currentMode() { return mode.getSelectedIndex() == 0 ? Mode.CHANGES : Mode.HISTORY; }
    private void switchMode() {
        boolean local = currentMode() == Mode.CHANGES; comparison.setVisible(local); scope.setVisible(!local); parent.setEnabled(!local); conflict.setVisible(false);
        historyFilters.setVisible(!local); jump.setVisible(!local); pair.setVisible(!local);
        showReferences.setVisible(!local); showDetails.setVisible(!local);
        if (local && files.getParent() != localBrowser) {
            if (detailsFiles.getHeight() > 0 && showDetails.isSelected()) fileSplit = fraction(detailsFiles);
            detailsFiles.setBottomComponent(null); localBrowser.add(files);
        } else if (!local && files.getParent() == localBrowser) {
            localBrowser.remove(files); detailsFiles.setBottomComponent(files);
            SwingUtilities.invokeLater(() -> detailsFiles.setDividerLocation(showDetails.isSelected() ? fileSplit : 0));
        }
        ((CardLayout)browsers.getLayout()).show(browsers, local ? "local" : "history");
        service.cancel(GitWorkspaceService.Request.SELECTION); service.cancel(GitWorkspaceService.Request.CONTENT); contentVersion++;
        if (local) { service.cancel(GitWorkspaceService.Request.HISTORY); service.cancel(GitWorkspaceService.Request.REFS); historyLoading = false; refsVersion = ""; }
        if (repository == null) return;
        if (local) { selectedPath = changesSelection; localFiles(); }
        else if (!historySelection.isEmpty()) { selectedPath = ""; service.commit(historySelection, this::showCommit, this::failure); }
        else { files.data(List.of(), ""); showMessage("Select a commit to inspect its message and changed files.\n\nSelect two commits to compare their trees in an explicit A → B direction."); diff.clear("Select a commit and file"); }
        if (!local && active && latestStatus != null && latestStatus.available()) displayStatus(latestStatus);
        revalidate();
    }
    public void showHistory() { mode.setSelectedIndex(1); }
    public void showLocalChanges() { mode.setSelectedIndex(0); comparison.setSelectedIndex(0); }
    private void restartHistory() {
        if (!active || repository == null || currentMode() != Mode.HISTORY) return;
        LinkedHashSet<String> tips = new LinkedHashSet<>();
        if (!selectedReference.isEmpty()) tips.add(selectedReference);
        else if (scope.getSelectedIndex() == 1) { if (!repository.head().isEmpty()) tips.add(repository.head()); }
        else { repository.refs().forEach(ref -> tips.add(ref.objectId())); if (!repository.head().isEmpty()) tips.add(repository.head()); }
        query = new HistoryQuery(List.copyOf(tips), search.getText(), author.getText(), path.getText());
        if (selectedReference.isEmpty()) filterState.setText(query.filtered() ? "Active · searching full history" : "");
        commits = List.of(); historyBytes = 0; hasMore = true; historyLoading = false; service.cancel(GitWorkspaceService.Request.HISTORY); loadMore();
    }
    private void loadMore() {
        if (!active || query == null || historyLoading || !hasMore || commits.size() >= 20000) return;
        historyLoading = true; history.loading(); int offset = commits.size();
        service.history(query, offset, 200, page -> {
            historyLoading = false;
            long pageBytes = page.commits().stream().mapToLong(commit -> 512L + 2L * (commit.id().length() + commit.author().length() + commit.date().length() + commit.subject().length()) + commit.parents().stream().mapToLong(id -> 32L + 2L * id.length()).sum()).sum();
            if (historyBytes + pageBytes > 8 * 1024 * 1024) {
                hasMore = false; history.data(commits, repository.refs(), query.filtered(), false, Set.of(historySelection)); history.limit("History memory limit · narrow the filters"); status.setText("8 MiB loaded history limit · narrow filters or jump directly to a commit"); return;
            }
            historyBytes += pageBytes;
            List<GitCommitHeader> loaded = new ArrayList<>(commits); loaded.addAll(page.commits()); commits = List.copyOf(loaded); hasMore = page.hasMore();
            Set<String> selected = new HashSet<>(); history.selection().forEach(commit -> selected.add(commit.id())); if (!historySelection.isEmpty()) selected.add(historySelection);
            history.data(commits, repository.refs(), query.filtered(), hasMore && commits.size() < 20000, selected);
            if (commits.size() >= 20000) { history.limit("20,000 row limit · narrow filters"); status.setText("20,000 visible commits limit · narrow history with filters"); }
        }, error -> { historyLoading = false; history.data(commits, repository.refs(), query.filtered(), hasMore, Set.of(historySelection)); failure(error); });
    }
    private void selectCommits(List<GitCommitHeader> selected) {
        if (selected.size() != 1 || repository == null) return;
        changing = true; mode.setSelectedIndex(1); changing = false; comparison.setVisible(false); scope.setVisible(true);
        historySelection = selected.getFirst().id(); selectedPath = ""; diff.updating(); contentReady = false; service.cancel(GitWorkspaceService.Request.CONTENT); service.commit(selected.getFirst().id(), this::showCommit, this::failure);
    }
    private void showCommit(GitCommit commit) {
        selectedCommit = commit; historySelection = commit.id();
        commitDetails.show(commit);
        changing = true; parent.removeAllItems();
        if (commit.parents().isEmpty()) parent.addItem("Empty tree · root commit"); else for (int i = 0; i < commit.parents().size(); i++) parent.addItem((i + 1) + " · " + commit.parents().get(i));
        parent.setSelectedIndex(0); parent.setEnabled(true); changing = false; commitFiles(commit);
    }
    private void commitFiles(GitCommit commit) {
        if (currentMode() != Mode.HISTORY) return;
        first = commit.parents().isEmpty() ? GitSource.empty() : GitSource.tree(commit.parents().get(Math.max(0, parent.getSelectedIndex())), "Parent " + (parent.getSelectedIndex() + 1));
        second = GitSource.tree(commit.id(), commit.shortId()); loadFiles();
    }
    private void localFiles() {
        if (repository == null) return; service.cancel(GitWorkspaceService.Request.SELECTION); contentVersion++;
        GitSource head = repository.head().isEmpty() ? GitSource.empty() : GitSource.tree(repository.head(), "HEAD · " + repository.head().substring(0, 10));
        first = comparison.getSelectedIndex() == 2 ? GitSource.index() : head;
        second = comparison.getSelectedIndex() == 1 ? GitSource.index() : GitSource.working();
        int comparisonIndex = comparison.getSelectedIndex();
        List<GitChange> visible = repository.changes().stream()
                .filter(change -> !change.untracked())
                .filter(change -> switch (comparisonIndex) {
                    case 1 -> change.index() != '.';
                    case 2 -> change.worktree() != '.';
                    default -> true;
                }).toList();
        showMessage(comparison.getSelectedItem() + "\n\nTracked files only. Untracked files are excluded; newly staged files are included.\n\nViewing Git's disk state. The editor may have pending autosave or an unresolved synchronization conflict.\n\nI / W: index and working-tree status. A path may differ in both. All local changes includes their union, even when staged and unstaged edits cancel in the net diff.\n\nUse Open working file in editor from a path’s context menu to edit it in the main editor.");
        parent.setEnabled(false); updateFiles(visible);
    }
    private void loadFiles() { contentReady = false; contentVersion++; service.cancel(GitWorkspaceService.Request.CONTENT); diff.updating(); service.changes(first, second, this::updateFiles, this::failure); }
    private void updateFiles(List<GitChange> changes) {
        String retain = currentMode() == Mode.CHANGES ? changesSelection : selectedPath;
        if (currentMode() == Mode.HISTORY && !navigationPath.isEmpty()) {
            String target = navigationPath; navigationPath = "";
            retain = changes.stream().filter(change -> change.path().equals(target) || change.oldPath().equals(target)).findFirst().map(GitChange::identity).orElse("");
        }
        files.data(changes, retain);
        GitChange selection = files.selection();
        if (selection != null) selectFile(selection);
        else if (retain.isEmpty() && !changes.isEmpty()) files.selectFirst();
        else diff.clear(changes.isEmpty() ? "No changed paths in this comparison" : "Selected path is no longer in this comparison · choose another file");
    }
    private void selectFile(GitChange change) {
        if (location == null) return;
        selectedPath = change.identity(); if (currentMode() == Mode.CHANGES) changesSelection = selectedPath;
        conflict.setVisible(change.unmerged()); GitSource before = change.unmerged() && conflict.getSelectedIndex() > 0 ? GitSource.index(conflict.getSelectedIndex()) : first;
        contentReady = false; long version = ++contentVersion; Path selectedRoot = project; GitSource after = second; diff.updating();
        service.comparison(before, after, change, loaded -> {
            if (version != contentVersion) return;
            contentReady = true; diff.show(loaded, before, after, change, after.kind() == GitSource.Kind.WORKTREE ? editorState.apply(selectedRoot.resolve(change.path())) : "", source -> { if (Objects.equals(project, selectedRoot)) openFile.accept(selectedRoot.resolve(source.path())); });
        }, error -> { if (version == contentVersion) { diff.clear(error); failure(error); } });
    }
    private void comparePair() {
        List<GitCommitHeader> selected = history.selection();
        if (selected.size() != 2) { status.setText("Select exactly two history rows, then choose Compare A → B"); return; }
        GitCommitHeader a = selected.get(1), b = selected.get(0);
        Object[] options = {a.shortId() + " → " + b.shortId(), b.shortId() + " → " + a.shortId(), "Cancel"};
        int direction = JOptionPane.showOptionDialog(this, "Choose explicit before (A) and after (B) trees", "Compare commits", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (direction == 0) compareCommits(a, b); else if (direction == 1) compareCommits(b, a);
    }
    public void compareCommits(GitCommitHeader a, GitCommitHeader b) {
        mode.setSelectedIndex(1); first = GitSource.tree(a.id(), "A · " + a.shortId()); second = GitSource.tree(b.id(), "B · " + b.shortId()); selectedPath = "";
        selectedCommit = null; parent.setEnabled(false); showMessage("Tree comparison · A → B\n\nA  " + a.id() + "\n" + a.subject() + "\n\nB  " + b.id() + "\n" + b.subject()); loadFiles();
    }
    private void pathHistory(GitChange change) { if (project != null) fileHistory.accept(project.resolve(change.path())); }
    private void jumpTo(GitCommit commit) { mode.setSelectedIndex(1); history.select(commit.id()); showCommit(commit); status.setText("Inspecting " + commit.id() + " · commit may lie outside the current history filters"); }
    private void open(GitChange change) { if (project != null) openFile.accept(project.resolve(change.path())); }
    private void failure(String message) { status.setText("Stale / unavailable · " + message + " · Refresh to retry"); status.setToolTipText(status.getText()); if (repository == null) diff.clear(message); }
    private void paneVisibility() {
        if (!showReferences.isSelected() && referencePane.isVisible() && referenceHistory.getWidth() > 0) savedReferences = fraction(referenceHistory);
        if (!showDetails.isSelected() && detailsPane.isVisible() && detailsFiles.getHeight() > 0) savedDetails = fraction(detailsFiles);
        referencePane.setVisible(showReferences.isSelected()); referenceHistory.setDividerSize(showReferences.isSelected() ? 5 : 0); referenceHistory.setDividerLocation(showReferences.isSelected() ? savedReferences : 0);
        detailsPane.setVisible(showDetails.isSelected()); detailsFiles.setDividerSize(showDetails.isSelected() ? 5 : 0); detailsFiles.setDividerLocation(showDetails.isSelected() ? savedDetails : 0); revalidate();
    }
    public void restore(GitViewState state) { changing = true; mode.setSelectedIndex(state.history() ? 1 : 0); scope.setSelectedIndex(state.currentBranch() ? 1 : 0); changing = false; diff.restore(state.unified(), state.ignoreWhitespace()); savedReferences = state.references(); savedDetails = state.files(); fileSplit = state.files(); showReferences.setSelected(state.showReferences()); showDetails.setSelected(state.showDetails()); switchMode(); SwingUtilities.invokeLater(() -> {
            vertical.setDividerLocation(state.vertical()); vertical.doLayout();
            referenceHistory.setDividerLocation(state.references()); referenceHistory.doLayout();
            historyDetails.setDividerLocation(state.details()); historyDetails.doLayout();
            detailsFiles.setDividerLocation(state.files()); detailsFiles.doLayout(); paneVisibility();
        }); }
    public GitViewState capture(GitViewState host) { return new GitViewState(host.x(), host.y(), host.width(), host.height(), host.maximized(), host.docked(), fraction(vertical), showReferences.isSelected() ? fraction(referenceHistory) : savedReferences, fraction(historyDetails), showDetails.isSelected() ? (currentMode() == Mode.CHANGES ? fileSplit : fraction(detailsFiles)) : savedDetails, showReferences.isSelected(), showDetails.isSelected(), diff.unified(), diff.ignoreWhitespace(), currentMode() == Mode.HISTORY, scope.getSelectedIndex() == 1); }
    private double fraction(JSplitPane split) { int extent = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight(); return extent == 0 ? split.getResizeWeight() : Math.clamp((double)split.getDividerLocation() / extent, .1, .9); }
    public void preserveViewState(Runnable update) {
        int dot = message.getCaret().getDot(), mark = message.getCaret().getMark();
        Map<JViewport, Point> positions = new IdentityHashMap<>(); captureViewports(this, positions);
        diff.preserveViewState(() -> {
            try { update.run(); }
            finally {
                message.setCaretPosition(Math.min(mark, message.getDocument().getLength())); message.moveCaretPosition(Math.min(dot, message.getDocument().getLength()));
                SwingUtilities.invokeLater(() -> { if (!closed) positions.forEach(JViewport::setViewPosition); });
            }
        });
    }
    private void captureViewports(Container root, Map<JViewport, Point> positions) {
        for (Component child : root.getComponents()) {
            if (child instanceof JViewport viewport) positions.put(viewport, viewport.getViewPosition());
            if (child instanceof Container container) captureViewports(container, positions);
        }
    }
    public void applySettings(TextEditorSettings settings, EditorTheme theme) { setBackground(theme.surface()); history.applyTheme(theme); files.applyTheme(theme); diff.applySettings(settings, theme); commitDetails.applyTheme(theme); UiTypography.interfaceFont(references, theme); references.setRowHeight(Math.max(26, theme.interfaceFont().getSize() + 12)); UiTypography.interfaceFont(status, theme); status.setForeground(theme.muted()); }
    public String statusText() { return status.getText(); }
    public boolean hasRepository() { return location != null; }
    @Override public void close() { if (closed) return; deactivate(); closed = true; try { statusSubscription.close(); } catch (Exception failure) { throw new IllegalStateException(failure); } service.close(); diff.close(); }
}
