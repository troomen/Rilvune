package dev.editor.app;

import dev.editor.agents.application.AgentService;
import dev.editor.agents.domain.AgentProvider;
import dev.editor.agents.domain.AgentEngine;
import dev.editor.agents.infrastructure.opencode.OpenCodeRuntime;
import dev.editor.agents.infrastructure.opencode.OpenCodeInstaller;
import dev.editor.agents.infrastructure.opencode.OpenCodeRuntimePaths;
import dev.editor.agents.ui.AgentsPanel;
import dev.editor.app.application.ProjectLifecycle;
import dev.editor.platform.theme.UiTypography;
import dev.editor.document.application.DocumentService;
import dev.editor.git.application.GitWorkspaceService;
import dev.editor.git.application.ProjectGitStatusService;
import dev.editor.git.infrastructure.*;
import dev.editor.git.domain.GitViewState;
import dev.editor.git.ui.*;
import dev.editor.platform.text.ui.TextEditorSettings;
import dev.editor.platform.text.domain.syntax.SyntaxCatalog;
import dev.editor.platform.text.domain.syntax.SyntaxLimits;
import dev.editor.platform.text.infrastructure.syntax.rsta.RstaSyntaxProvider;
import dev.editor.platform.text.ui.syntax.SyntaxProjectionRegistry;
import dev.editor.settings.application.SettingsService;
import dev.editor.workspace.domain.Workspace;
import dev.editor.workspace.domain.WorkspaceFiles;
import dev.editor.document.infrastructure.FileDraftRepository;
import dev.editor.document.infrastructure.NioDocumentStore;
import dev.editor.workspace.infrastructure.NioWorkspaceFiles;
import dev.editor.session.infrastructure.SessionRepository;
import dev.editor.settings.infrastructure.PropertiesSettingsStore;
import dev.editor.workspace.infrastructure.WorkspaceOperations;
import dev.editor.app.ui.MainWindow;
import dev.editor.document.ui.DocumentBindings;
import dev.editor.diff.infrastructure.MyersDiffEngine;
import dev.editor.diff.infrastructure.DiffLimits;
import dev.editor.settings.ui.SettingsDialog;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.FontCatalog;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Composition root: runtime paths, dependencies, executors and lifecycle are all assembled explicitly. */
public final class EditorApplication {
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private EditorApplication() { }

    public static void main(String[] arguments) {
        if (arguments.length == 1 && arguments[0].equals("--help")) {
            System.out.println("Editor [--workspace <directory>] [--browse-root <directory>] [--profile <directory>] [--open <file>] [--compare <other file>] [--snapshot <png>] [--snapshot-view editor|appearance|font]");
            return;
        }
        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        SyntaxLimits syntaxLimits = SyntaxLimits.interactive();
        ExecutorService syntaxWorkers = Executors.newFixedThreadPool(syntaxLimits.workers(), Thread.ofPlatform().daemon(true).name("syntax-", 0).factory());
        try {
            UiTypography.initialize();
            LaunchOptions options = LaunchOptions.parse(arguments);
            Path browseRoot = options.browseRoot().toRealPath();
            if (!Files.isDirectory(browseRoot)) throw new IOException("BROWSE_ROOT must be a directory");
            Files.createDirectories(options.profile());
            Path profile = options.profile().toRealPath();
            WorkspaceFiles files = new NioWorkspaceFiles(MAX_FILE_BYTES);
            Workspace workspace = options.workspace() == null ? null : files.openWorkspace(options.workspace());
            FontCatalog.registerBundled();
            FontCatalog fonts = new FontCatalog();
            SettingsService settings = new SettingsService(new PropertiesSettingsStore(profile), io, SwingUtilities::invokeLater);
            SessionRepository sessions = new SessionRepository(profile);
            WorkspaceOperations operations = new WorkspaceOperations(browseRoot, profile);
            DocumentService documents = new DocumentService(new NioDocumentStore(MAX_FILE_BYTES, profile.resolve("file-locks"), browseRoot),
                    new FileDraftRepository(profile.resolve("drafts")), io, scheduler, SwingUtilities::invokeLater, Duration.ofSeconds(2));
            var gitStore = new PropertiesGitViewStateStore(profile);
            GitViewState gitInitial;
            try { gitInitial = gitStore.load(); } catch (IOException failure) { System.err.println(failure.getMessage()); gitInitial = GitViewState.defaults(); }
            GitViewState gitState = gitInitial;
            var diffEngine = new MyersDiffEngine(DiffLimits.interactive());
            var gitRepository = new CliGitRepository(GitConfiguration.defaults());
            var gitObservation = new GitObservation(io);
            SwingUtilities.invokeLater(() -> {
                SyntaxProjectionRegistry syntax = new SyntaxProjectionRegistry(new SyntaxCatalog(java.util.List.of(new RstaSyntaxProvider().contribution())), syntaxLimits, syntaxWorkers);
                try {
                    EditorTheme theme = EditorTheme.from(settings.current()); theme.install();
                    MainWindow window = new MainWindow(theme, workspace, files, documents, new DocumentBindings(documents, io), diffEngine, settings, sessions, operations, browseRoot, fonts, io,
                            () -> { syntax.close(); syntaxWorkers.shutdown(); scheduler.shutdownNow(); io.shutdown(); });
                    window.installSyntax(syntax);
                    var value = settings.current();
                    var projectGit = new ProjectGitStatusService(gitRepository, gitObservation, io, scheduler, SwingUtilities::invokeLater,
                            ProjectGitStatusService.Configuration.defaults(), failure -> System.err.println(failure.getMessage()));
                    var textSettings = new TextEditorSettings(value.tabSize(), value.useSpaces(), value.softWrap(), value.showLineNumbers(), value.highlightCurrentLine(), value.showMinimap(), value.syntaxHighlighting());
                    window.installHistory(new FileHistoryWindow(window, new GitWorkspaceService(gitRepository, io, SwingUtilities::invokeLater),
                            diffEngine, io, textSettings, theme, window::openFile));
                    var gitWorkspace = new GitWorkspacePanel(new GitWorkspaceService(gitRepository, io, SwingUtilities::invokeLater), projectGit,
                            diffEngine, io, textSettings, theme, window::openFile, window::showFileHistory, window::gitEditorState);
                    window.installGit(new GitWindow(window, gitWorkspace, window::setGitDock, gitStore, gitState, io), projectGit);
                    window.installEditorGit(new dev.editor.git.application.EditorGitService(gitRepository, projectGit, io, SwingUtilities::invokeLater,
                            dev.editor.git.application.EditorGitService.Configuration.defaults(), error -> System.err.println(error.getMessage())),
                            java.time.Clock.systemUTC(), java.time.ZoneId.systemDefault(), java.util.Locale.getDefault());
                    var openCodePaths = new OpenCodeRuntimePaths(profile);
                    var agent = new AgentService(new OpenCodeInstaller(openCodePaths.bin()),
                            (executable, project, provider, events) -> new OpenCodeRuntime(executable, project, provider, events, openCodePaths),
                            new AgentEngine("opencode", "OpenCode"), new AgentProvider("openai", "Codex", "openai/", "ChatGPT"), new dev.editor.agents.infrastructure.persistence.FileAgentChatStateStore(profile), io, scheduler, SwingUtilities::invokeLater,
                            project -> documents.flushAll().thenAccept(snapshots -> {
                                if (snapshots.stream().anyMatch(snapshot -> snapshot.path() != null && snapshot.path().startsWith(project)
                                        && (snapshot.state() != dev.editor.document.domain.SyncState.SYNCHRONIZED || snapshot.needsAttention())))
                                    throw new IllegalStateException("Resolve editor synchronization problems before sending to the agent");
                            }), () -> {
                                documents.refreshAll(); projectGit.invalidate(); SwingUtilities.invokeLater(window.projectTree()::refreshPreservingState);
                            });
                    agent.settings(settings.current().agents());
                    var agentPanel = new AgentsPanel(agent, theme, uri -> CompletableFuture.runAsync(() -> {
                        try { dev.editor.platform.desktop.SystemBrowser.browse(uri); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CompletionException(interrupted); }
                        catch (Exception unavailable) { throw new java.util.concurrent.CompletionException(unavailable); }
                    }, io));
                    agentPanel.settings(settings.current().agents());
                    var agentSettingsSubscription = settings.listen(updated -> {
                        agentPanel.settings(updated.agents()); agent.settings(updated.agents());
                    });
                    window.installAgents(agentPanel, new ProjectLifecycle.Participant() {
                        public String name() { return "AI Assistant"; }
                        public CompletableFuture<Void> quiesce() { return agent.quiesce(); }
                        public void resume(dev.editor.workspace.domain.ProjectContext context) { agent.project(context == null ? null : context.root()); }
                    }, agentPanel::applyTheme, () -> {
                        try { agentSettingsSubscription.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                        agentPanel.close(); agent.close();
                    });
                    window.setVisible(true); window.sizeLayout();
                    CompletableFuture<Void> ready = window.loadProject();
                    if (options.openFile().isPresent()) ready = ready.thenComposeAsync(ignored -> window.openFile(options.openFile().orElseThrow()), SwingUtilities::invokeLater);
                    if (options.compareFile().isPresent()) ready = ready.thenComposeAsync(ignored -> window.openComparison(options.openFile().orElseThrow(), options.compareFile().orElseThrow())
                            .thenCompose(tab -> tab.ready()), SwingUtilities::invokeLater);
                    if (options.snapshot().isPresent()) ready.whenCompleteAsync((ignored, failure) -> {
                        if (failure != null) { failSnapshot(window, failure); return; }
                        capture(options, window, io);
                    }, SwingUtilities::invokeLater);
                } catch (Exception failure) { syntax.close(); syntaxWorkers.shutdownNow(); failure.printStackTrace(System.err); scheduler.shutdownNow(); io.shutdownNow(); System.exit(1); }
            });
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Cannot start Editor: " + failure.getMessage()); syntaxWorkers.shutdownNow(); scheduler.shutdownNow(); io.shutdownNow(); System.exit(1);
        }
    }
    private static void capture(LaunchOptions options, MainWindow window, ExecutorService io) {
        SettingsDialog dialog = options.snapshotView().equals("editor") ? null : window.settingsDialog();
        if (dialog != null) { dialog.selectPage(options.snapshotView()); dialog.setModal(false); dialog.setVisible(true); }
        Timer settle = new Timer(700, event -> {
            window.sizeLayout();
            JComponent content = dialog == null ? window.snapshotComponent() : dialog.getRootPane();
            BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); content.printAll(graphics); graphics.dispose();
            Path output = options.snapshot().orElseThrow();
            CompletableFuture.runAsync(() -> {
                try { Files.createDirectories(output.getParent()); if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("PNG writer unavailable"); System.out.println("Snapshot: " + output); }
                catch (IOException exception) { throw new CompletionException(exception); }
            }, io).whenCompleteAsync((ignored, failure) -> {
                if (dialog != null) dialog.dispose();
                if (failure != null) failSnapshot(window, failure); else window.finishSnapshot();
            }, SwingUtilities::invokeLater);
        });
        settle.setRepeats(false); settle.start();
    }
    private static void failSnapshot(MainWindow window, Throwable failure) { System.err.println("Cannot create snapshot: " + failure.getMessage()); window.finishSnapshot().whenComplete((ignored, closeFailure) -> System.exit(1)); }
}
