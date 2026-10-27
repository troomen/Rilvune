package dev.rilvune.app.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.app.application.FileOpenCoordinator;
import dev.rilvune.app.application.ProjectLifecycle;
import dev.rilvune.app.ui.about.AboutDialog;
import dev.rilvune.comparison.application.FileComparisonService;
import dev.rilvune.comparison.ui.ComparisonTabView;
import dev.rilvune.comparison.ui.ComparisonTabs;
import dev.rilvune.diff.domain.DiffEngine;
import dev.rilvune.diff.domain.TextSnapshot;
import dev.rilvune.diff.ui.SnapshotDiffDialog;
import dev.rilvune.document.application.DocumentService;
import dev.rilvune.document.application.DocumentSession;
import dev.rilvune.document.domain.DocumentSnapshot;
import dev.rilvune.document.domain.LineEnding;
import dev.rilvune.document.domain.TextFormat;
import dev.rilvune.document.ui.DocumentBindings;
import dev.rilvune.document.ui.EditorView;
import dev.rilvune.document.ui.TextTabs;
import dev.rilvune.editor.application.EditorFileOpeners;
import dev.rilvune.editor.application.EditorTabRestorers;
import dev.rilvune.editor.ui.EditorTabView;
import dev.rilvune.git.application.ProjectGitStatusService;
import dev.rilvune.git.domain.FileHistoryQuery;
import dev.rilvune.git.ui.FileHistoryWindow;
import dev.rilvune.git.ui.GitProjectDecorations;
import dev.rilvune.git.ui.GitWindow;
import dev.rilvune.image.infrastructure.ImageFileLoader;
import dev.rilvune.image.infrastructure.ImageMemoryBudget;
import dev.rilvune.image.ui.ImageTabs;
import dev.rilvune.platform.command.domain.Command;
import dev.rilvune.platform.command.domain.CommandId;
import dev.rilvune.platform.command.domain.CommandRegistry;
import dev.rilvune.platform.command.ui.CommandPalette;
import dev.rilvune.platform.layout.domain.Axis;
import dev.rilvune.platform.layout.domain.LayoutNode;
import dev.rilvune.platform.layout.domain.Panel;
import dev.rilvune.platform.layout.domain.Split;
import dev.rilvune.platform.layout.ui.PanelRegistry;
import dev.rilvune.platform.layout.ui.SwingLayoutRenderer;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import dev.rilvune.platform.ui.Glyph;
import dev.rilvune.platform.ui.SurfacePanel;
import dev.rilvune.platform.ui.UiComponents;
import dev.rilvune.search.application.ProjectReplacement;
import dev.rilvune.search.application.ProjectSearch;
import dev.rilvune.search.ui.ProjectSearchDialog;
import dev.rilvune.session.domain.SessionState;
import dev.rilvune.session.infrastructure.SessionRepository;
import dev.rilvune.settings.application.SettingsService;
import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.settings.ui.SettingsDialog;
import dev.rilvune.workspace.application.ProjectDocuments;
import dev.rilvune.workspace.domain.Workspace;
import dev.rilvune.workspace.domain.WorkspaceFiles;
import dev.rilvune.workspace.infrastructure.WorkspaceOperations;
import dev.rilvune.workspace.infrastructure.WorkspaceWatcher;
import dev.rilvune.workspace.ui.PathChooser;
import dev.rilvune.workspace.ui.ProjectTreeView;
import dev.rilvune.workspace.ui.WorkspaceActions;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import javax.swing.*;

/** Window composition and lifecycle. Documents, preferences and filesystem mutations have separate owners. */
public final class MainWindow extends JFrame {
    private EditorTheme theme;
    private Workspace workspace;
    private final WorkspaceFiles files;
    private final DocumentService documents;
    private final DocumentBindings bindings;
    private final ProjectReplacement replacements;
    private final JButton retryReplacementCleanup = new JButton("Retry replacement cleanup");
    private final JButton recoveryWarning = new JButton();
    private final ComparisonTabs comparisons;
    private final DiffEngine diffEngine;
    private final Set<SnapshotDiffDialog> snapshotComparisons = new java.util.HashSet<>();
    private final SettingsService settings;
    private final SessionRepository sessions;
    private final WorkspaceOperations operations;
    private final ExecutorService io;
    private final Path browseRoot;
    private final FontCatalog fonts;
    private final Runnable shutdown;
    private final CommandRegistry commands = new CommandRegistry();
    private final java.util.Map<String, java.util.function.BooleanSupplier> targetCommands = new java.util.LinkedHashMap<>();
    private final PanelRegistry panels = new PanelRegistry();
    private GitWindow git;
    private FileHistoryWindow history;
    private ProjectGitStatusService projectGit;
    private dev.rilvune.git.application.EditorGitService editorGit;
    private AutoCloseable projectGitSubscription;
    private java.util.Set<Path> treeStatusPaths = java.util.Set.of(), tabStatusPaths = java.util.Set.of();
    private final JPanel agentsHost = new JPanel(new BorderLayout());
    private ProjectLifecycle.Participant agentsLifecycle;
    private java.util.function.Consumer<EditorTheme> agentsPresentation = ignored -> { };
    private Runnable agentsCleanup = () -> { };
    private final JPanel editorHost = new JPanel(new BorderLayout());
    private final JPanel shell = new JPanel(new BorderLayout());
    private final JPanel layoutHost = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel("Ready");
    private final TextTabs editor;
    private final ImageTabs images;
    private final EditorFileOpeners fileOpeners = new EditorFileOpeners();
    private final EditorTabRestorers tabRestorers = new EditorTabRestorers();
    private final ProjectTreeView tree;
    private final PathChooser chooser;
    private final WorkspaceActions fileActions;
    private final WorkspaceWatcher watcher;
    private final Timer persistence;
    private final Timer refresh;
    private final AutoCloseable settingsSubscription;
    private final AutoCloseable gitDocumentSubscription;
    private SwingLayoutRenderer renderer;
    private JSplitPane projectSplit;
    private boolean projectVisible = true;
    private boolean agentsVisible;
    private double projectFraction = 0.23;
    private boolean changingProject;
    private boolean stopped;
    private boolean savingSession;
    private boolean wordWrapSaving;
    private dev.rilvune.platform.text.ui.syntax.SyntaxSupport syntax = dev.rilvune.platform.text.ui.syntax.SyntaxSupport.none();
    private final ProjectLifecycle lifecycle;
    private final ProjectDocuments projectDocuments;
    private long generation;
    private List<Path> recent = List.of();
    private long openRequest;
    private final FileOpenCoordinator textFileOpener;
    private final JMenu recentMenu = new JMenu("Recent Projects");

    public MainWindow(EditorTheme theme, Workspace workspace, WorkspaceFiles files, DocumentService documents, DocumentBindings bindings, DiffEngine diffEngine,
                      SettingsService settings, SessionRepository sessions, WorkspaceOperations operations,
                      Path browseRoot, FontCatalog fonts, ExecutorService io, Runnable shutdown) throws IOException {
        super("Rilvune");
        this.theme = theme; this.workspace = workspace; this.files = files; this.documents = documents; this.bindings = bindings;
        this.settings = settings; this.sessions = sessions; this.operations = operations; this.browseRoot = browseRoot;
        this.fonts = fonts; this.io = io; this.shutdown = shutdown;
        this.diffEngine = diffEngine;
        textFileOpener = new FileOpenCoordinator(path -> supplyIo(() -> checkedExisting(path))
                .thenCompose(documents::acquire), SwingUtilities::invokeLater);
        replacements = new ProjectReplacement(documents, SwingUtilities::invokeLater, io,
                () -> SwingUtilities.invokeLater(this::replacementCleanupChanged));
        chooser = new PathChooser(browseRoot);
        tree = new ProjectTreeView(theme, files, io, this::openFile, this::setStatus);
        editor = new TextTabs(bindings, theme, settings.current(), this::setStatus, view -> saveCopy(view, false),
                path -> tree.select(path), this::newDraft, this::chooseProject, this::comparisonAction, this::inspectConflict);
        var imageBudget = ImageMemoryBudget.standard();
        var projectImages = new ImageFileLoader(browseRoot, ImageFileLoader.Limits.standard(), imageBudget);
        Path attachmentDirectory = sessions.profile().toAbsolutePath().normalize().resolve("agents/attachments");
        images = new ImageTabs(editor, (path, canceled) -> path.startsWith(attachmentDirectory)
                ? new ImageFileLoader(attachmentDirectory.toRealPath(), ImageFileLoader.Limits.standard(), imageBudget).load(path, canceled)
                : projectImages.load(path, canceled), io, () -> this.theme);
        fileOpeners.register(ImageFileLoader::isImage,
                (path, request) -> images.open(path, "image:" + java.util.UUID.randomUUID(), () -> request == openRequest));
        fileOpeners.register(path -> true, (path, request) -> openTextFile(path, request).thenApply(ignored -> null));
        editor.setPathContextActions(path -> List.of(fileHistoryAction(path)));
        editor.setViewEnhancer(this::enhanceEditorView);

        comparisons = new ComparisonTabs(new FileComparisonService(documents, browseRoot, io), bindings, editor,
                diffEngine, io, settings::current, () -> this.theme, view -> saveCopy(view, false), path -> tree.select(path), this::setStatus, this::inspectConflict);
        fileActions = new WorkspaceActions(this, () -> this.workspace, operations, documents, editor, tree, io, chooser, this::openFile, this::comparisonAction, this::setStatus);
        fileActions.setMutationListener(images::mutation);
        tabRestorers.register(SessionState.TextTabDescriptor.class, this::restoreTextTab);
        tabRestorers.register(SessionState.ComparisonTabDescriptor.class, state -> comparisons.restore(state).thenApply(ignored -> state.id()));
        tabRestorers.register(SessionState.ImageTabDescriptor.class, state -> restoreImageTab(state));
        tree.setContextMenuProvider(selection -> {
            JPopupMenu menu = fileActions.menu(selection);
            selection.filter(path -> !Files.isDirectory(path)).ifPresent(path -> {
                menu.addSeparator(); menu.add(fileSearchAction(path, false)); menu.add(fileSearchAction(path, true));
                menu.addSeparator(); menu.add(fileHistoryAction(path));
            });
            if (this.workspace != null) {
                menu.addSeparator(); menu.add(commandItem("navigate.text")); menu.add(commandItem("navigate.replace"));
            }
            return menu;
        });
        editor.setLifecycleAllowed(() -> !fileActions.busy());
        watcher = new WorkspaceWatcher(io, paths -> SwingUtilities.invokeLater(() -> {
            if (!stopped && !changingProject) { tree.refreshBranches(paths); if (projectGit != null) projectGit.invalidate(); }
        }), failure -> SwingUtilities.invokeLater(() -> setStatus("Filesystem watch: " + failure.getMessage())));
        tree.setExpandedDirectoriesListener(paths -> runIo(() -> { watcher.watch(paths); return null; }, ignored -> { }));
        registerCommands(); editor.setActiveTabListener(this::updateTargetCommands); registerPanels();
        shell.setBackground(theme.chrome()); shell.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
        shell.add(rail(), BorderLayout.WEST); shell.add(statusBar(), BorderLayout.SOUTH);
        layoutHost.setOpaque(false); layoutHost.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 7)); shell.add(layoutHost, BorderLayout.CENTER);
        setContentPane(shell); setJMenuBar(menuBar());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); setMinimumSize(new Dimension(960, 620)); setSize(1440, 900); setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { requestExit(); }
            @Override public void windowClosed(WindowEvent event) { stopInfrastructure(); }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent event) {
                if (!stopped) { images.checkActive(); documents.refreshAll(); if (projectGit != null) projectGit.refresh(); if (MainWindow.this.workspace != null) tree.refreshPreservingState(); }
            }
        });
        settingsSubscription = settings.listen(this::applySettings);
        gitDocumentSubscription = documents.addListener(snapshot -> {
            if (!stopped && snapshot.path() != null) {
                if (git != null) git.workspace().editorChanged(snapshot.path());
                if (projectGit != null && snapshot.state() == dev.rilvune.document.domain.SyncState.SYNCHRONIZED) projectGit.invalidate();
            }
        });
        persistence = new Timer(2000, event -> persistSession()); persistence.start();
        refresh = new Timer(3000, event -> { if (!changingProject) images.checkActive(); if (!changingProject && this.workspace != null) tree.refreshPreservingState(); }); refresh.start();
        projectDocuments = new ProjectDocuments(documents, files, SwingUtilities::invokeLater, io);
        lifecycle = createLifecycle();
        applySettings(settings.current()); rebuildLayout(); updateProjectName();
    }

    public CompletableFuture<Void> loadProject() {
        return projectOperation(lifecycle.initialize(), "Restore session").whenCompleteAsync((ignored, failure) -> {
            settings.warning().ifPresent(this::setStatus);
            if (!fonts.missingFamilies(settings.current()).isEmpty()) setStatus("Unavailable font: " + String.join(", ", fonts.missingFamilies(settings.current())) + "; using Hack. Open Settings to choose a replacement.");
            updateRecoveryWarning();
        }, SwingUtilities::invokeLater);
    }
    /** The assistant view owns its header and dock controls. */
    public void installAgents(JComponent view, ProjectLifecycle.Participant participant, java.util.function.Consumer<EditorTheme> presentation, Runnable cleanup) {
        if (agentsLifecycle != null) throw new IllegalStateException("Agent feature already installed");
        agentsLifecycle = participant; agentsPresentation = presentation; agentsCleanup = cleanup;
        agentsHost.removeAll(); agentsHost.add(view); UiTypography.apply(view, theme); agentsHost.revalidate(); agentsHost.repaint();
    }
    public void hideAgents() { if (agentsVisible) execute("view.agents"); }
    public void installGit(GitWindow git, ProjectGitStatusService statuses) {
        if (this.git != null) throw new IllegalStateException("Git feature already installed");
        this.git = git; this.projectGit = statuses;
        git.workspace().syntaxSupport(syntax);
        projectGitSubscription = statuses.subscribe(snapshot -> {
            getRootPane().getActionMap().get("view.git").setEnabled(snapshot.available());
            getRootPane().getActionMap().get("view.gitChanges").setEnabled(snapshot.available());
            var decorations = GitProjectDecorations.from(snapshot);
            tree.setDecorations(decorations); editor.setDecorations(decorations);
        });
        git.project(workspace == null ? null : workspace.root()); statuses.project(workspace == null ? null : workspace.root()); git.applySettings(textSettings(settings.current()), theme);
        tree.setMaterializedFilesListener(paths -> { treeStatusPaths = paths; updateStatusPaths(); });
        editor.setFilePathsListener(paths -> { tabStatusPaths = paths; updateStatusPaths(); });
    }
    public void installEditorGit(dev.rilvune.git.application.EditorGitService inspection, java.time.Clock clock, java.time.ZoneId zone, java.util.Locale locale) {
        if (editorGit != null || git == null) throw new IllegalStateException("Install editor inspection once after Git");
        editorGit = inspection;
        editor.setViewEnhancer(view -> {
            enhanceEditorView(view);
            view.installExtension(new dev.rilvune.git.ui.editor.EditorGitController(inspection,
                    view.documentAccess(), view.textEditor(), diffEngine, io, theme, clock, zone, locale, git::showCommit, this::setStatus));
        });
    }
    public void installHistory(FileHistoryWindow history) {
        if (this.history != null) throw new IllegalStateException("File history already installed");
        this.history = history;
        history.syntaxSupport(syntax);
    }
    public void installSyntax(dev.rilvune.platform.text.ui.syntax.SyntaxSupport support) {
        syntax = java.util.Objects.requireNonNull(support);
        editor.views().forEach(view -> view.installSyntax(support)); comparisons.syntaxSupport(support);
        if (git != null) git.workspace().syntaxSupport(support);
        if (history != null) history.syntaxSupport(support);
    }
    private void enhanceEditorView(EditorView view) {
        view.installSyntax(syntax);
        view.textEditor().setMenuFactory(text -> {
            JPopupMenu menu = TextEditor.standardMenu(text); menu.addSeparator();
            menu.add(fileHistoryAction(view.session().path())); menu.add(selectionHistoryAction(view.session(), text)); return menu;
        });
    }
    private boolean historyAvailable(Path file) {
        return history != null && git != null && git.workspace().hasRepository() && workspace != null && file != null
                && file.startsWith(workspace.root()) && !changingProject && !stopped;
    }
    public void showFileHistory(Path file) {
        if (historyAvailable(file)) history.open(workspace.root(), file, null, null);
    }
    private Action fileHistoryAction(Path file) {
        Action action = action(() -> showFileHistory(file)); action.putValue(Action.NAME, "Show File History");
        action.setEnabled(historyAvailable(file)); return action;
    }
    private Action selectionHistoryAction(DocumentSession session, TextEditor text) {
        Path file = session.path();
        Action action = action(() -> {
            if (!historyAvailable(file) || !Objects.equals(file, session.path()) || session.isClosed()) return;
            JTextArea area = text.textComponent(); int start = area.getSelectionStart(), end = area.getSelectionEnd();
            if (start == end) return;
            try {
                var lines = new FileHistoryQuery.Lines(area.getLineOfOffset(start), area.getLineOfOffset(end - 1) + 1);
                history.open(workspace.root(), file, area.getText(), lines);
            } catch (javax.swing.text.BadLocationException failure) { throw new IllegalStateException(failure); }
        });
        action.putValue(Action.NAME, "Show Selection History");
        action.setEnabled(historyAvailable(file) && text.textComponent().getSelectionStart() != text.textComponent().getSelectionEnd());
        return action;
    }
    private void updateStatusPaths() {
        if (projectGit == null || stopped) return;
        var paths = new java.util.HashSet<>(treeStatusPaths); paths.addAll(tabStatusPaths);
        projectGit.observedPaths(paths);
    }
    public void setGitDock(JComponent content) {
        editorHost.removeAll();
        if (content == null) editorHost.add(editor);
        else { JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, content); split.setResizeWeight(.4); split.setContinuousLayout(true); split.setDividerSize(5); split.setBorder(BorderFactory.createEmptyBorder()); editorHost.add(split); SwingUtilities.invokeLater(() -> split.setDividerLocation(.4)); }
        editorHost.revalidate(); editorHost.repaint();
    }
    public String gitEditorState(Path path) {
        return documents.sessions().stream().filter(session -> path.equals(session.path())).map(session -> session.snapshot().state()).filter(state -> state != dev.rilvune.document.domain.SyncState.SYNCHRONIZED).map(state -> "Rilvune: " + state + " · Git shows disk only").findFirst().orElse("");
    }
    public JComponent snapshotComponent() { return getRootPane(); }
    public TextTabs editorTabs() { return editor; }
    public ProjectTreeView projectTree() { return tree; }
    public Workspace currentWorkspace() { return workspace; }
    public dev.rilvune.workspace.domain.ProjectContext projectContext() { return lifecycle.context(); }
    public ProjectDocuments projectDocuments() { return projectDocuments; }
    public void sizeLayout() { validate(); if (renderer != null) renderer.applyInitialSizes(); validate(); }
    public SettingsDialog settingsDialog() { return new SettingsDialog(this, theme, settings, fonts, syntax); }

    public CompletableFuture<Void> openFile(Path requested) {
        if (changingProject || stopped || fileActions.busy()) return CompletableFuture.completedFuture(null);
        long request = ++openRequest, epoch = generation;
        Path path = requested.isAbsolute() ? requested : (workspace == null ? browseRoot : workspace.root()).resolve(requested);
        // An existing mutable text model remains authoritative until explicitly closed.
        if (editor.views().stream().anyMatch(view -> path.toAbsolutePath().normalize().equals(view.session().path())))
            return openTextFile(path, request).thenApply(ignored -> null);
        return supplyIo(() -> {
            Path real = checkedExisting(path);
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + path);
            return fileOpeners.select(real);
        }).thenComposeAsync(file -> {
            if (stopped || changingProject || epoch != generation) return CompletableFuture.completedFuture(null);
            var existingText = editor.views().stream().filter(view -> file.path().equals(view.session().path())).findFirst();
            if (existingText.isPresent()) return openTextFile(file.path(), request).thenApply(ignored -> null);
            return file.open(request);
        }, SwingUtilities::invokeLater).whenCompleteAsync((ignored, failure) -> {
            if (failure != null && !stopped && epoch == generation) error("Open file", failure);
        }, SwingUtilities::invokeLater);
    }

    /** Explicit attachment navigation never gives a private snapshot mutable file ownership. */
    public CompletableFuture<Void> openAttachmentSnapshot(Path file, String name, String mimeType) {
        if (changingProject || stopped || fileActions.busy()) return CompletableFuture.completedFuture(null);
        long request = ++openRequest, epoch = generation;
        return supplyIo(() -> {
            Path path = checkedAttachmentSnapshot(file);
            if (mimeType.startsWith("image/")) return new AttachmentOpen(path, null);
            if (mimeType.equals("text/plain")) return new AttachmentOpen(path, Files.readString(path));
            throw new IOException("This attachment type cannot be displayed in the editor: " + mimeType);
        }).thenComposeAsync(value -> {
            if (stopped || changingProject || epoch != generation || request != openRequest) return CompletableFuture.completedFuture(null);
            if (value.text() == null) return images.openSnapshot(value.path(), name, "image:" + UUID.randomUUID(), () -> request == openRequest);
            EditorView view = editor.show(documents.createDraft());
            view.textComponent().setText(value.text());
            setStatus("Draft copy of attachment · " + name);
            return CompletableFuture.completedFuture(null);
        }, SwingUtilities::invokeLater).whenCompleteAsync((ignored, failure) -> {
            if (failure != null && !stopped && epoch == generation) error("Open attachment", failure);
        }, SwingUtilities::invokeLater);
    }
    private record AttachmentOpen(Path path, String text) { }
    private boolean isAttachmentSnapshot(Path path) {
        Path directory = sessions.profile().toAbsolutePath().normalize().resolve("agents/attachments");
        Path normalized = path.toAbsolutePath().normalize();
        return directory.equals(normalized.getParent()) && normalized.getFileName().toString().matches("[a-f0-9]{64}\\.(png|jpg|gif|webp|pdf|txt)");
    }
    private Path checkedAttachmentSnapshot(Path requested) throws IOException {
        if (!isAttachmentSnapshot(requested)) throw new IOException("Not a saved attachment snapshot");
        Path path = requested.toAbsolutePath().normalize();
        Path directory = sessions.profile().toAbsolutePath().normalize().resolve("agents/attachments").toRealPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !directory.equals(path.toRealPath().getParent()))
            throw new IOException("Attachment is missing or is not a regular saved snapshot");
        if (Files.size(path) > (20L << 20)) throw new IOException("Attachment exceeds 20 MiB");
        return path;
    }

    private CompletableFuture<java.util.UUID> openTextDocument(Path requested) {
        if (changingProject || stopped || fileActions.busy()) return CompletableFuture.completedFuture(null);
        long requestEpoch = generation;
        return openTextFileInCurrentProject(requested).whenCompleteAsync((id, failure) -> {
            if (failure != null && requestEpoch == generation && !changingProject && !stopped) error("Open file", failure);
        }, SwingUtilities::invokeLater);
    }

    /** Session restoration may open files during a transition; user actions wait until it finishes. */
    private CompletableFuture<java.util.UUID> openTextFileInCurrentProject(Path requested) {
        return openTextFile(requested, ++openRequest);
    }
    private CompletableFuture<java.util.UUID> openTextFile(Path requested, long request) {
        if (stopped) return CompletableFuture.completedFuture(null);
        Path path = requested.isAbsolute() ? requested : (workspace == null ? browseRoot : workspace.root()).resolve(requested);
        long requestGeneration = generation;
        Path normalized = path.toAbsolutePath().normalize();
        for (EditorView view : editor.views()) {
            if (normalized.equals(view.session().path())) {
                var closing = editor.pendingClose(view.session().id());
                if (closing.isPresent()) return closing.get().thenComposeAsync(accepted -> retryTextOpen(path, request, requestGeneration), SwingUtilities::invokeLater);
                if (view.session().isClosed()) { editor.remove(view); continue; }
                if (view.session().isClosing()) return view.session().close().thenComposeAsync(ignored -> retryTextOpen(path, request, requestGeneration), SwingUtilities::invokeLater);
                if (request == openRequest) editor.select(view.session().id()); return CompletableFuture.completedFuture(view.session().id());
            }
        }
        setStatus("Opening " + path.getFileName() + "…");
        return textFileOpener.open(path, handle -> {
            DocumentSession session = handle.session();
            boolean transferred = editor.adopt(handle, request == openRequest);
            if (request == openRequest) setStatus(session.path().toString());
            return new FileOpenCoordinator.Adoption<>(session.id(), transferred);
        }).whenCompleteAsync((ignored, failure) -> {
            if (!stopped && requestGeneration == generation) {
                updateRecoveryWarning();
            }
        }, SwingUtilities::invokeLater);
    }
    private CompletableFuture<java.util.UUID> retryTextOpen(Path path, long request, long requestEpoch) {
        if (stopped || requestEpoch != generation || request != openRequest) return CompletableFuture.completedFuture(null);
        return openTextFile(path, request);
    }
    public CompletableFuture<Void> openProject(Path path) {
        if (fileActions.busy() || stopped) return CompletableFuture.completedFuture(null);
        return projectOperation(lifecycle.open(path), "Open project");
    }
    private CompletableFuture<Void> restoreViews(Workspace next, SessionState saved, boolean restoreWindow) {
        projectFraction = saved.projectFraction(); projectVisible = saved.projectVisible(); agentsVisible = saved.agentsVisible();
        if (restoreWindow) restoreBounds(saved); rebuildLayout(); sizeLayout();
        CompletableFuture<Void> chain = tree.showWorkspace(next);
        java.util.Map<String, String> restoredIds = new java.util.HashMap<>();
        for (var descriptor : saved.tabs()) {
            chain = chain.thenComposeAsync(ignored -> tabRestorers.restore(descriptor).thenAcceptAsync(id -> {
                if (id != null) restoredIds.put(descriptor.id(), id);
            }, SwingUtilities::invokeLater).exceptionally(failure -> null), SwingUtilities::invokeLater);
        }
        return chain.thenRunAsync(() -> {
            for (DocumentSession document : documents.sessions()) {
                if (document.path() != null && document.path().startsWith(next.root()) && document.snapshot().needsAttention()) editor.show(document);
            }
            editor.restoreOrder(saved.tabOrder().stream().map(id -> restoredIds.getOrDefault(id, id)).toList());
            editor.select(restoredIds.getOrDefault(saved.activeId(), saved.activeId()));
            loadRecent(); setStatus("");
        }, SwingUtilities::invokeLater);
    }
    private CompletableFuture<String> restoreTextTab(SessionState.TextTabDescriptor file) {
        DocumentSession recovered = documents.sessions().stream().filter(document -> document.id().toString().equals(file.id())
                || file.path() != null && file.path().equals(document.path())).findFirst().orElse(null);
        if (recovered != null) { editor.show(recovered, false).caret(file.caret()); return CompletableFuture.completedFuture(recovered.id().toString()); }
        if (file.path() == null) return CompletableFuture.completedFuture(null);
        return openTextFileInCurrentProject(file.path()).thenApplyAsync(id -> {
            if (id != null) editor.views().stream().filter(view -> view.session().id().equals(id)).findFirst().ifPresent(view -> view.caret(file.caret()));
            return id == null ? null : id.toString();
        }, SwingUtilities::invokeLater);
    }
    private CompletableFuture<String> restoreImageTab(SessionState.ImageTabDescriptor state) {
        Path normalized = state.path().toAbsolutePath().normalize();
        if (isAttachmentSnapshot(normalized))
            return supplyIo(() -> checkedAttachmentSnapshot(normalized)).thenApplyAsync(path -> images.restore(path, state.id()), SwingUtilities::invokeLater);
        if (!normalized.startsWith(browseRoot)) return CompletableFuture.completedFuture(null);
        return supplyIo(() -> {
            try { return checkedExisting(normalized); }
            catch (NoSuchFileException failure) {
                Path parent = normalized.getParent();
                while (parent != null && !Files.exists(parent)) parent = parent.getParent();
                if (parent == null || !parent.toRealPath().startsWith(browseRoot)) throw new IOException("Image is outside BROWSE_ROOT");
                return normalized;
            }
        }).thenApplyAsync(path -> images.restore(path, state.id()), SwingUtilities::invokeLater);
    }
    private void restoreBounds(SessionState saved) {
        Rectangle available = getGraphicsConfiguration().getBounds();
        int width = Math.min(saved.width(), available.width); int height = Math.min(saved.height(), available.height);
        int x = saved.x() < 0 ? available.x + (available.width - width) / 2 : Math.clamp(saved.x(), available.x, available.x + available.width - width);
        int y = saved.y() < 0 ? available.y + (available.height - height) / 2 : Math.clamp(saved.y(), available.y, available.y + available.height - height);
        setBounds(x, y, width, height);
    }
    private void closeProject() {
        if (!fileActions.busy() && !stopped) projectOperation(lifecycle.closeProject(), "Close project");
    }
    private void chooseProject() { chooser.choose(this, workspace == null ? browseRoot : workspace.root(), true, false, "Open project").ifPresent(this::openProject); }
    public void createProject() {
        if (changingProject || fileActions.busy()) return;
        chooser.choose(this, browseRoot, true, false, "New project · choose parent directory").ifPresent(parent -> {
            String name = JOptionPane.showInputDialog(this, "Project directory name:", "New project", JOptionPane.PLAIN_MESSAGE);
            if (name == null) return;
            if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) { setStatus("Enter a single directory name."); return; }
            Path target = parent.resolve(name);
            runIo(() -> {
                if (Files.isDirectory(target)) return target;
                return operations.createDirectory(target);
            }, this::openProject);
        });
    }
    private void newDraft() {
        if (changingProject || fileActions.busy()) return;
        editor.show(documents.createDraft(new TextFormat(false, LineEnding.valueOf(settings.current().defaultLineEnding()))));
    }
    private void chooseFile() { chooser.choose(this, workspace == null ? browseRoot : workspace.root(), false, false, "Open file").ifPresent(this::openFile); }
    private void saveCopy(EditorView view, boolean assign) {
        Path base = view.session().path() == null ? (workspace == null ? browseRoot : workspace.root()) : view.session().path().getParent();
        chooser.choose(this, base, false, true, assign ? "Save as" : "Save this version as").ifPresent(path -> {
            view.setEditingEnabled(false);
            supplyIo(() -> checkedDestination(path)).thenCompose(target -> assign ? view.session().assignPath(target).thenApply(ignored -> target) : view.session().saveCopy(target))
                    .whenCompleteAsync((result, failure) -> {
                        view.setEditingEnabled(true);
                        if (failure != null) error(assign ? "Assign path" : "Save copy", failure);
                        else { tree.refreshPreservingState(); setStatus("Written to " + path); }
                    }, SwingUtilities::invokeLater);
        });
    }
    private void showDrafts() {
        documents.restoreDrafts(path -> path == null || path.startsWith(browseRoot)).whenCompleteAsync((restored, failure) -> {
            if (failure != null) { error("Recovery", failure); return; }
            updateRecoveryWarning();
            String problems = documents.recoveryProblems().stream().map(dev.rilvune.document.domain.DraftRepository.Problem::description)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            List<DocumentSession> drafts = documents.sessions().stream().filter(document -> document.path() == null || document.snapshot().needsAttention()).toList();
            if (drafts.isEmpty()) {
                if (problems.isEmpty()) setStatus("No drafts or unresolved documents");
                else JOptionPane.showMessageDialog(this, recoveryProblemsView(problems), "Recovery warnings", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JList<DocumentSession> list = new JList<>(drafts.toArray(DocumentSession[]::new)); list.setSelectedIndex(0);
            list.setCellRenderer((component, document, index, selected, focus) -> {
                JLabel label = new JLabel(document.snapshot().name() + "  ·  " + EditorView.stateLabel(document.snapshot()) + "  ·  " + document.text().replace('\n', ' ').substring(0, Math.min(document.text().length(), 50)));
                label.setOpaque(true); label.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                label.setBackground(selected ? component.getSelectionBackground() : component.getBackground()); return label;
            });
            JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(850, 320));
            Object[] options = {"Open", "Delete unnamed draft", "Cancel"};
            JPanel content = new JPanel(new BorderLayout(0, 8)); content.add(scroll);
            if (!problems.isEmpty()) content.add(recoveryProblemsView(problems), BorderLayout.NORTH);
            int answer = JOptionPane.showOptionDialog(this, content, "Drafts & recovery", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            DocumentSession document = list.getSelectedValue(); if (document == null) return;
            if (answer == 0) editor.show(document);
            if (answer == 1 && document.path() == null && JOptionPane.showConfirmDialog(this, "Permanently delete this unnamed draft?", "Delete draft", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                document.discardDraft().whenCompleteAsync((ignored, error) -> {
                    if (error != null) error("Delete draft", error);
                    else for (EditorView view : editor.views()) if (view.session() == document) editor.remove(view);
                }, SwingUtilities::invokeLater);
        }, SwingUtilities::invokeLater);
    }
    private JComponent recoveryProblemsView(String problems) {
        JTextArea text = new JTextArea("Some recovery records could not be read. Existing recovery files were left untouched.\n\n" + problems, 5, 85);
        text.setName("recoveryProblems"); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setCaretPosition(0);
        return new JScrollPane(text);
    }
    private void updateRecoveryWarning() {
        if (stopped) return;
        int count = documents.recoveryProblems().size();
        recoveryWarning.setText("Recovery warnings (" + count + ")");
        recoveryWarning.setVisible(count > 0);
        recoveryWarning.setToolTipText("Some recovery records could not be read. Click for details and retry.");
    }
    public ComparisonTabs fileComparisons() { return comparisons; }
    public CompletableFuture<ComparisonTabView> openComparison(Path left, Path right) {
        if (changingProject || stopped || fileActions.busy()) return CompletableFuture.failedFuture(new IllegalStateException("Project is changing"));
        return comparisons.open(new SessionState.Source("", absoluteSource(left)), new SessionState.Source("", absoluteSource(right)));
    }
    private Path absoluteSource(Path path) { return path.isAbsolute() ? path : (workspace == null ? browseRoot : workspace.root()).resolve(path); }
    private Action comparisonAction(Path path) { return comparisonAction(new SessionState.Source("", absoluteSource(path))); }
    private Action comparisonAction(DocumentSession session) { return comparisonAction(new SessionState.Source(session.id().toString(), session.path())); }
    private Action comparisonAction(SessionState.Source source) {
        Action action = comparisons.compareWithCurrentAction(source);
        if (changingProject || stopped || fileActions.busy()) action.setEnabled(false);
        return action;
    }
    private void disposeSearchDialogs() {
        for (Window owned : getOwnedWindows()) if (owned instanceof ProjectSearchDialog dialog) dialog.dispose();
    }
    private void searchProject(boolean names) { searchProject(names, false); }
    private Action fileSearchAction(Path path, boolean replace) {
        Action action = action(() -> {
            if (changingProject || stopped || fileActions.busy()) return;
            openTextDocument(path).thenAcceptAsync(id -> editor.views().stream()
                    .filter(view -> view.session().id().equals(id)).findFirst()
                    .ifPresent(view -> view.textEditor().find(replace)), SwingUtilities::invokeLater);
        });
        action.putValue(Action.NAME, replace ? "Replace in file…" : "Find in file…");
        action.setEnabled(!changingProject && !stopped && !fileActions.busy());
        return action;
    }
    private void searchProject(boolean names, boolean replace) {
        if (changingProject || stopped || fileActions.busy()) return;
        if (workspace == null) { setStatus("Open a project to search its files"); return; }
        Set<String> excluded = settings.current().showHiddenFiles() ? Set.of() : Set.of(".git", ".gradle", ".cache", ".local", ".idea", "build", "node_modules");
        new ProjectSearchDialog(this, theme, workspace, new ProjectSearch(files, 1000, excluded), replacements, io, names, replace,
                (path, line) -> {
                    if (names) openFile(path);
                    else openTextDocument(path).thenAcceptAsync(id -> editor.views().stream()
                            .filter(view -> view.session().id().equals(id)).findFirst()
                            .ifPresent(view -> view.goToLine(Math.max(1, line))), SwingUtilities::invokeLater);
                }, this::comparisonAction).setVisible(true);
    }
    private void registerCommands() {
        register("project.new", "New project…", null, this::createProject);
        register("project.open", "Open project…", "ctrl alt O", this::chooseProject);
        register("project.close", "Close project", null, this::closeProject);
        register("file.new", "New draft", "ctrl N", this::newDraft);
        register("file.open", "Open file…", "ctrl O", this::chooseFile);
        registerTarget("file.assign", "Save as…", "ctrl shift S", () -> editor.active().isPresent(), () -> editor.active().ifPresent(view -> saveCopy(view, true)));
        register("file.close", "Close tab", "ctrl W", editor::requestCloseActive);
        register("file.closeAll", "Close all tabs", null, editor::closeAll);
        register("file.drafts", "Drafts & recovery…", null, this::showDrafts);
        register("file.directory", "New directory in Project…", null, () -> fileActions.createSelected(true));
        registerTarget("edit.undo", "Undo", "ctrl Z", () -> editor.activeTextEditor().isPresent(), () -> activeText(TextEditor::undo));
        registerTarget("edit.redo", "Redo", "ctrl shift Z", () -> editor.activeTextEditor().isPresent(), () -> activeText(TextEditor::redo));
        registerTarget("edit.find", "Find in file…", "ctrl F", () -> editor.activeTextEditor().isPresent(), () -> activeText(view -> view.find(false)));
        registerTarget("edit.replace", "Replace in file…", "ctrl R", () -> editor.activeTextEditor().isPresent(), () -> activeText(view -> view.find(true)));
        registerTarget("navigate.line", "Go to line…", "ctrl G", () -> editor.activeTextEditor().isPresent(), () -> activeText(TextEditor::goToLineDialog));
        registerTarget("view.wordWrap", "Word Wrap", "alt Z", () -> !wordWrapSaving && !stopped, this::toggleWordWrap);
        register("navigate.file", "Find file…", "ctrl shift N", () -> searchProject(true));
        register("navigate.text", "Find in project…", "ctrl shift F", () -> searchProject(false));
        register("navigate.replace", "Replace in project…", "ctrl shift R", () -> searchProject(false, true));
        register("navigate.next", "Next tab", "ctrl TAB", () -> editor.next(1));
        register("navigate.previous", "Previous tab", "ctrl shift TAB", () -> editor.next(-1));
        register("view.project", "Toggle Project", "alt 1", () -> { captureFraction(); projectVisible = !projectVisible; rebuildLayout(); sizeLayout(); });
        register("view.agents", "Toggle AI Assistant", "alt 2", () -> { captureFraction(); agentsVisible = !agentsVisible; rebuildLayout(); sizeLayout(); });
        register("view.git", "Git History", "alt 9", () -> { if (git != null && git.workspace().hasRepository()) git.showHistory(); });
        register("view.gitChanges", "Local Changes", "alt 0", () -> { if (git != null && git.workspace().hasRepository()) git.showLocalChanges(); });
        registerTarget("git.fileHistory", "Show File History", null, () -> editor.activeFilePath().isPresent(), () -> editor.activeFilePath().ifPresent(this::showFileHistory));
        registerTarget("git.selectionHistory", "Show Selection History", null, () -> editor.activeTextEditor().isPresent(), () -> editor.activeDocument().ifPresent(session ->
                editor.activeTextEditor().ifPresent(text -> selectionHistoryAction(session, text).actionPerformed(null))));
        getRootPane().getActionMap().get("view.git").setEnabled(false);
        getRootPane().getActionMap().get("view.gitChanges").setEnabled(false);
        register("view.reset", "Reset panel layout", null, () -> { projectVisible = true; agentsVisible = false; projectFraction = 0.23; rebuildLayout(); sizeLayout(); });
        register("settings", "Settings…", "ctrl alt S", () -> settingsDialog().setVisible(true));
        register("commands", "Find action…", "ctrl shift P", () -> new CommandPalette(commands, theme).show(this));
        register("about", "About Rilvune…", null, () -> new AboutDialog(this, theme, sessions.profile()).setVisible(true));
        register("exit", "Exit", "alt F4", this::requestExit);
        tree.component().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("F2"), "rename-file");
        tree.component().getActionMap().put("rename-file", action(fileActions::renameSelected));
        tree.component().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("DELETE"), "trash-file");
        tree.component().getActionMap().put("trash-file", action(fileActions::deleteSelected));
    }
    private void registerTarget(String id, String title, String shortcut, java.util.function.BooleanSupplier available, Runnable handler) {
        CommandId command = new CommandId(id);
        commands.register(new Command(command, title, handler, available));
        Action swingAction = action(() -> commands.execute(command)); swingAction.putValue(Action.NAME, title);
        getRootPane().getActionMap().put(id, swingAction); targetCommands.put(id, available);
        if (shortcut != null) getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(shortcut), id);
    }
    private void updateTargetCommands() {
        targetCommands.forEach((id, available) -> getRootPane().getActionMap().get(id).setEnabled(available.getAsBoolean()));
        getRootPane().getActionMap().get("view.wordWrap").putValue(Action.SELECTED_KEY,
                settings.current().softWrap());
    }
    private void toggleWordWrap() {
        if (wordWrapSaving || stopped) return;
        wordWrapSaving = true; updateTargetCommands();
        settings.apply(settings.current().withSoftWrap(!settings.current().softWrap())).whenCompleteAsync((saved, failure) -> {
            wordWrapSaving = false; updateTargetCommands();
            if (failure != null) error("Word Wrap", failure);
        }, SwingUtilities::invokeLater);
    }
    private void register(String id, String title, String shortcut, Runnable handler) {
        CommandId command = new CommandId(id); commands.register(new Command(command, title, handler));
        Action swingAction = action(handler); swingAction.putValue(Action.NAME, title);
        getRootPane().getActionMap().put(id, swingAction);
        if (shortcut != null) getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(shortcut), id);
    }
    private AbstractAction action(Runnable callback) { return new AbstractAction() { public void actionPerformed(ActionEvent event) { callback.run(); } }; }
    private void activeText(Consumer<TextEditor> action) { editor.activeTextEditor().ifPresent(action); }
    private void execute(String id) { commands.execute(new CommandId(id)); }
    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = menu("File", "project.new", "project.open"); file.add(recentMenu); file.add(commandItem("project.close"));
        file.addSeparator();
        for (String id : List.of("file.new", "file.directory", "file.open", "file.assign", "file.drafts")) file.add(commandItem(id));
        file.addSeparator(); file.add(commandItem("file.close")); file.add(commandItem("file.closeAll"));
        file.addSeparator(); file.add(commandItem("exit")); bar.add(file);
        bar.add(menu("Edit", "edit.undo", "edit.redo", "-", "edit.find", "edit.replace", "-", "settings"));
        bar.add(menu("Search", "edit.find", "edit.replace", "-", "navigate.text", "navigate.replace", "-", "navigate.file"));
        bar.add(menu("Navigate", "navigate.line", "-", "navigate.next", "navigate.previous"));
        bar.add(menu("View", "view.project", "view.agents", "view.git", "view.gitChanges", "-", "view.wordWrap", "-", "git.fileHistory", "git.selectionHistory", "-", "view.reset"));
        bar.add(menu("Help", "commands", "-", "about")); return bar;
    }
    private JMenu menu(String title, String... ids) { JMenu menu = new JMenu(title); for (String id : ids) { if (id.equals("-")) menu.addSeparator(); else menu.add(commandItem(id)); } return menu; }
    private JMenuItem commandItem(String id) {
        JMenuItem item = id.equals("view.wordWrap")
                ? new JCheckBoxMenuItem(getRootPane().getActionMap().get(id))
                : new JMenuItem(getRootPane().getActionMap().get(id));
        KeyStroke[] keys = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).keys();
        if (keys != null) for (KeyStroke key : keys) if (id.equals(getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(key))) item.setAccelerator(key);
        return item;
    }
    private JComponent rail() {
        JPanel rail = new JPanel(); rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS)); rail.setBackground(theme.chrome()); rail.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 6));
        rail.add(UiComponents.iconButton(theme, Glyph.Kind.FOLDER, "Project · Alt+1", () -> execute("view.project"))); rail.add(Box.createVerticalStrut(12));
        rail.add(UiComponents.iconButton(theme, Glyph.Kind.SEARCH, "Find in project · Ctrl+Shift+F", () -> searchProject(false))); rail.add(Box.createVerticalGlue());
        rail.add(UiComponents.iconButton(theme, Glyph.Kind.CHAT, "AI Assistant · Alt+2", () -> execute("view.agents"))); return rail;
    }
    private JComponent statusBar() {
        JPanel bar = new JPanel(new BorderLayout()); bar.setBackground(theme.chrome()); bar.setBorder(BorderFactory.createEmptyBorder(6, 12, 4, 12));
        status.setForeground(theme.muted()); bar.add(status);
        retryReplacementCleanup.setName("retryReplacementCleanup"); retryReplacementCleanup.setVisible(false);
        retryReplacementCleanup.addActionListener(event -> {
            retryReplacementCleanup.setEnabled(false);
            replacements.retryCleanup().whenCompleteAsync((ignored, failure) -> {
                replacementCleanupChanged();
                if (failure != null) error("Replacement cleanup", failure);
            }, SwingUtilities::invokeLater);
        });
        recoveryWarning.setName("recoveryWarnings"); recoveryWarning.setVisible(false);
        recoveryWarning.addActionListener(event -> showDrafts());
        JPanel warnings = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); warnings.setOpaque(false);
        warnings.add(recoveryWarning); warnings.add(retryReplacementCleanup);
        bar.add(warnings, BorderLayout.EAST); return bar;
    }
    private void replacementCleanupChanged() {
        if (stopped) return;
        boolean wasPending = retryReplacementCleanup.isVisible();
        boolean pending = replacements.cleanupPending();
        retryReplacementCleanup.setVisible(pending); retryReplacementCleanup.setEnabled(pending);
        if (pending) setStatus("Replacement cleanup needs a retry: " + replacements.cleanupProblems().stream()
                .map(dev.rilvune.search.application.ReplacementProblem::description).collect(java.util.stream.Collectors.joining("; ")));
        else if (wasPending) setStatus("Replacement cleanup completed");
    }
    private void registerPanels() {
        SurfacePanel project = new SurfacePanel(theme); project.setMinimumSize(new Dimension(180, 100));
        project.add(UiComponents.header(theme, "Project",
                UiComponents.iconButton(theme, Glyph.Kind.COLLAPSE, "Collapse directories", tree::collapse), UiComponents.iconButton(theme, Glyph.Kind.MINUS, "Hide Project", () -> execute("view.project"))), BorderLayout.NORTH);
        project.add(tree.component());
        SurfacePanel center = new SurfacePanel(theme); center.setMinimumSize(new Dimension(360, 100)); editorHost.add(editor); center.add(editorHost);
        SurfacePanel agents = new SurfacePanel(theme); agents.setMinimumSize(new Dimension(260, 100));
        agentsHost.add(UiComponents.header(theme, "AI Assistant", UiComponents.iconButton(theme, Glyph.Kind.MINUS, "Hide AI Assistant", this::hideAgents)), BorderLayout.NORTH);
        agents.add(agentsHost);
        panels.register("project", project); panels.register("editor", center); panels.register("agents", agents);
    }
    private void rebuildLayout() {
        LayoutNode layout = new Panel("editor");
        if (agentsVisible) layout = new Split(Axis.HORIZONTAL, 0.76, layout, new Panel("agents"));
        if (projectVisible) layout = new Split(Axis.HORIZONTAL, projectFraction, new Panel("project"), layout);
        renderer = new SwingLayoutRenderer(panels); JComponent content = renderer.render(layout);
        projectSplit = projectVisible ? (JSplitPane) content : null;
        layoutHost.removeAll(); layoutHost.add(content); layoutHost.revalidate(); layoutHost.repaint();
    }
    private void captureFraction() { if (projectSplit != null && projectSplit.getWidth() > 0) projectFraction = Math.clamp((double) projectSplit.getDividerLocation() / projectSplit.getWidth(), 0.1, 0.7); }
    private void applySettings(EditorSettings value) {
        editor.preserveViewState(() -> { if (git == null) applyPresentation(value); else git.preserveViewState(() -> applyPresentation(value)); });
    }
    private void applyPresentation(EditorSettings value) {
        theme = EditorTheme.from(value); theme.install();
        SwingUtilities.updateComponentTreeUI(this);
        UiTypography.apply(this, theme);
        tree.applyTheme(theme); tree.setShowExcludedDirectories(value.showHiddenFiles()); editor.applySettings(value, theme);
        if (git != null) git.applySettings(textSettings(value), theme);
        if (history != null) history.applySettings(textSettings(value), theme);
        agentsPresentation.accept(theme);
        updateTargetCommands();
        for (SnapshotDiffDialog dialog : snapshotComparisons) {
            dialog.preserveViewState(() -> {
                SwingUtilities.updateComponentTreeUI(dialog); UiTypography.apply(dialog, theme); dialog.applySettings(textSettings(value), theme);
            });
        }
        UiTypography.interfaceFontAtSize(status, theme, Font.PLAIN, Math.max(11f, theme.interfaceFont().getSize2D() - 2));
        revalidate(); repaint();
    }
    private TextEditorSettings textSettings(EditorSettings value) {
        return new TextEditorSettings(value.tabSize(), value.useSpaces(), value.softWrap(), value.showLineNumbers(), value.highlightCurrentLine(), value.showMinimap(), value.syntaxHighlighting());
    }
    private void inspectConflict(DocumentSnapshot snapshot) {
        String description = snapshot.path() == null ? snapshot.name() : snapshot.path().toString();
        var source = snapshot.path() == null ? dev.rilvune.platform.text.domain.syntax.SyntaxSource.draft()
                : dev.rilvune.platform.text.domain.syntax.SyntaxSource.file(snapshot.path().getFileName().toString());
        SnapshotDiffDialog dialog = new SnapshotDiffDialog(this, "Compare · " + snapshot.name(),
                new SnapshotDiffDialog.Source("Rilvune · preserved", description,
                        new TextSnapshot(snapshot.id() + ":editor", snapshot.revision(), snapshot.text()), source),
                new SnapshotDiffDialog.Source("Disk · latest observed", description,
                        new TextSnapshot(snapshot.id() + ":disk", snapshot.revision(), snapshot.externalText()), source),
                textSettings(settings.current()), theme, diffEngine, io, this::setStatus, syntax);
        snapshotComparisons.add(dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { snapshotComparisons.remove(dialog); }
        });
        dialog.setVisible(true);
    }
    private void updateProjectName() { if (git != null) git.project(workspace == null ? null : workspace.root()); if (projectGit != null) projectGit.project(workspace == null ? null : workspace.root()); String name = workspace == null ? "Rilvune" : workspace.root().getFileName().toString(); setTitle(name + (workspace == null ? "" : " — Rilvune")); }
    private void loadRecent() {
        runIo(sessions::recentProjects, entries -> {
            recent = entries;
            recentMenu.removeAll();
            for (Path path : recent) recentMenu.add(recentItem(path));
            if (recent.isEmpty()) { JMenuItem empty = new JMenuItem("No recent projects"); empty.setEnabled(false); recentMenu.add(empty); }
            else { recentMenu.addSeparator(); JMenuItem clear = new JMenuItem("Clear recent projects"); clear.addActionListener(event -> runIo(() -> { for (Path path : recent) sessions.forget(path); return null; }, ignored -> loadRecent())); recentMenu.add(clear); }
            JPanel welcome = new JPanel(); welcome.setLayout(new BoxLayout(welcome, BoxLayout.Y_AXIS));
            JLabel heading = new JLabel(workspace == null ? "Welcome to Rilvune" : "Make something useful."); UiTypography.interfaceFont(heading, theme, Font.BOLD, 5); welcome.add(heading); welcome.add(Box.createVerticalStrut(24));
            welcome.add(welcomeButton("New project…", this::createProject)); welcome.add(Box.createVerticalStrut(8)); welcome.add(welcomeButton("Open project…    Ctrl+Alt+O", this::chooseProject));
            welcome.add(Box.createVerticalStrut(8)); welcome.add(welcomeButton("New draft        Ctrl+N", this::newDraft));
            if (workspace == null && !recent.isEmpty()) {
                welcome.add(Box.createVerticalStrut(26)); JLabel label = new JLabel("Recent projects"); label.setForeground(theme.muted()); welcome.add(label); welcome.add(Box.createVerticalStrut(8));
                for (Path path : recent.stream().limit(6).toList()) { welcome.add(welcomeButton(path.getFileName() + "    " + path, () -> openProject(path))); welcome.add(Box.createVerticalStrut(5)); }
            }
            welcome.add(Box.createVerticalStrut(20)); welcome.add(welcomeButton("Drafts & recovery…", this::showDrafts)); editor.setWelcome(welcome);
        });
    }
    private JMenuItem recentItem(Path path) {
        JMenuItem item = new JMenuItem(path.getFileName() + "    " + path);
        item.setEnabled(path.startsWith(browseRoot));
        item.setToolTipText(path.startsWith(browseRoot) ? path.toString() : "Outside BROWSE_ROOT; restart with a wider directory to open this project");
        item.addActionListener(event -> openProject(path)); return item;
    }
    private JButton welcomeButton(String text, Runnable callback) { JButton button = new JButton(text); button.setAlignmentX(Component.LEFT_ALIGNMENT); button.addActionListener(event -> callback.run()); return button; }
    private SessionState captureSession() {
        if (workspace == null) return null; captureFraction();
        var descriptors = editor.tabs().stream().map(EditorTabView::descriptor).toList();
        return new SessionState(workspace.root(), descriptors, editor.activeId(), getX(), getY(), getWidth(), getHeight(), projectFraction, projectVisible, agentsVisible, editor.tabOrder());
    }
    private void persistSession() {
        if (changingProject || stopped || savingSession || workspace == null) return;
        savingSession = true;
        lifecycle.persist().whenCompleteAsync((ignored, failure) -> {
            savingSession = false;
            if (failure != null) setStatus("Session could not be saved: " + message(failure));
        }, SwingUtilities::invokeLater);
    }
    private void requestExit() {
        if (!fileActions.busy() && !stopped) projectOperation(lifecycle.exit(), "Exit");
    }
    public CompletableFuture<Void> finishSnapshot() {
        return projectOperation(lifecycle.finishSnapshot(), "Snapshot shutdown");
    }
    private CompletableFuture<Void> projectOperation(CompletableFuture<ProjectLifecycle.Result> operation, String title) {
        return operation.thenApply(result -> {
            if (result.outcome() == ProjectLifecycle.Outcome.FAILED) throw new CompletionException(result.failure());
            return (Void) null;
        }).whenCompleteAsync((ignored, failure) -> { if (failure != null) error(title, failure); }, SwingUtilities::invokeLater);
    }
    private ProjectLifecycle createLifecycle() {
        ProjectLifecycle.Host host = new ProjectLifecycle.Host() {
            public void transitioning(boolean active) {
                changingProject = active;
                if (active) { generation++; disposeSearchDialogs(); if (history != null) history.clear(); }
            }
            public SessionState capture() { return captureSession(); }
            public CompletableFuture<Boolean> closeViews() { return editor.closeAllForProject(); }
            public void prepareRestore() { textFileOpener.resume(); images.resume(); }
            public CompletableFuture<Void> restore(Workspace next, SessionState saved, boolean bounds) { return restoreViews(next, saved, bounds); }
            public void publish(Workspace next) {
                workspace = next;
                if (next == null) tree.clear();
                updateProjectName(); updateStatusPaths(); loadRecent();
            }
            public void warning(Throwable failure) { setStatus(message(failure)); }
            public CompletableFuture<Void> finish() { return flushGit().thenRunAsync(MainWindow.this::dispose, SwingUtilities::invokeLater); }
        };
        return new ProjectLifecycle(workspace, sessions, path -> files.openWorkspace(checkedExisting(path)), host,
                List.of(new ProjectLifecycle.Participant() {
                            public String name() { return "agent runtime"; }
                            public CompletableFuture<Void> quiesce() { return agentsLifecycle == null ? CompletableFuture.completedFuture(null) : agentsLifecycle.quiesce(); }
                            public void resume(dev.rilvune.workspace.domain.ProjectContext context) { if (agentsLifecycle != null) agentsLifecycle.resume(context); }
                        }, participant("image previews", images::quiesce, images::resume),
                        participant("file opens", textFileOpener::quiesce, textFileOpener::resume),
                        participant("text view cleanup", editor::quiesce, () -> { }),
                        participant("project replacement", replacements::quiesce, replacements::resume),
                        participant("comparisons", comparisons::quiesce, () -> { }),
                        new ProjectLifecycle.Participant() {
                            public String name() { return "project document consumers"; }
                            public CompletableFuture<Void> quiesce() { return projectDocuments.quiesce(); }
                            public void resume(dev.rilvune.workspace.domain.ProjectContext context) { projectDocuments.resume(context); }
                        }),
                () -> documents.restoreDrafts(path -> path == null || path.toAbsolutePath().normalize().startsWith(browseRoot)).thenApply(ignored -> null),
                documents::close, SwingUtilities::invokeLater, io);
    }
    private static ProjectLifecycle.Participant participant(String name, java.util.function.Supplier<CompletableFuture<Void>> stop, Runnable resume) {
        return new ProjectLifecycle.Participant() {
            public String name() { return name; }
            public CompletableFuture<Void> quiesce() { return stop.get(); }
            public void resume(dev.rilvune.workspace.domain.ProjectContext context) { resume.run(); }
        };
    }
    private CompletableFuture<Void> flushGit() { return git == null ? CompletableFuture.completedFuture(null) : git.flush(); }
    private void stopInfrastructure() {
        if (stopped) return; stopped = true; persistence.stop(); refresh.stop();
        try { settingsSubscription.close(); gitDocumentSubscription.close(); watcher.close(); } catch (Exception failure) { System.err.println(message(failure)); }
        if (projectGitSubscription != null) try { projectGitSubscription.close(); } catch (Exception failure) { System.err.println(message(failure)); }
        if (git != null) git.close();
        if (history != null) history.close();
        if (projectGit != null) projectGit.close();
        editor.disposeAfterShutdown();
        images.close();
        if (editorGit != null) editorGit.close();
        bindings.close();
        agentsCleanup.run();
        shutdown.run();
    }
    private Path checkedExisting(Path path) throws IOException { Path real = path.toRealPath(); if (!real.startsWith(browseRoot)) throw new IOException("Path is outside BROWSE_ROOT: " + path); return real; }
    private Path checkedDestination(Path path) throws IOException { Path normalized = path.toAbsolutePath().normalize(); if (!normalized.startsWith(browseRoot) || !normalized.getParent().toRealPath().startsWith(browseRoot)) throw new IOException("Path is outside BROWSE_ROOT: " + path); return normalized; }
    private <T> CompletableFuture<T> supplyIo(IoSupplier<T> task) { return CompletableFuture.supplyAsync(() -> { try { return task.get(); } catch (IOException failure) { throw new CompletionException(failure); } }, io); }
    private <T> void runIo(IoSupplier<T> task, Consumer<T> complete) { supplyIo(task).whenCompleteAsync((value, failure) -> { if (failure != null) error("Rilvune", failure); else if (!stopped) complete.accept(value); }, SwingUtilities::invokeLater); }
    private void setStatus(String message) { status.setText(message == null ? "" : message); status.setToolTipText(message); }
    private void error(String title, Throwable failure) { if (stopped) return; setStatus(message(failure)); JOptionPane.showMessageDialog(this, message(failure), title, JOptionPane.ERROR_MESSAGE); }
    private String message(Throwable failure) {
        Throwable cause = dev.rilvune.search.application.ReplacementProblem.unwrap(failure);
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
}
