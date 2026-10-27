package dev.editor.git.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.git.domain.*;
import dev.editor.platform.text.ui.TextEditorSettings;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Reparents one workspace. Closing a host pauses queries; it never closes the main editor. */
public final class GitWindow implements AutoCloseable {
    private final JFrame frame = new JFrame("Git");
    private final JPanel surface = new JPanel(new BorderLayout());
    private final JButton dock = new JButton("Dock");
    private final JLabel title = new JLabel("Git · read only");
    private final GitWorkspacePanel workspace;
    private final Consumer<JComponent> dockHost;
    private final GitViewStateStore store;
    private final ExecutorService workers;
    private GitViewState saved;
    private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
    private boolean visible, closed, docked;
    private Path project;
    private Rectangle normalBounds;
    public GitWindow(Window owner, GitWorkspacePanel workspace, Consumer<JComponent> dockHost, GitViewStateStore store,
                     GitViewState initial, ExecutorService workers) {
        this.workspace = workspace; this.dockHost = dockHost; this.store = store; this.workers = workers; saved = initial; docked = initial.docked();
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); frame.setMinimumSize(new Dimension(760, 500));
        frame.setIconImages(owner.getIconImages());
        JPanel heading = new JPanel(new BorderLayout()); heading.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8)); heading.add(title);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); dock.setName("git.dock"); dock.addActionListener(event -> setDocked(!docked)); actions.add(dock);
        JButton hide = new JButton("Hide"); hide.setName("git.hide"); hide.addActionListener(event -> hide()); actions.add(hide); heading.add(actions, BorderLayout.EAST);
        surface.add(heading, BorderLayout.NORTH); surface.add(workspace); frame.setContentPane(new JPanel(new BorderLayout()));
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { hide(); } @Override public void windowActivated(WindowEvent e) { if (visible) workspace.refresh(); } });
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "hide-git");
        frame.getRootPane().getActionMap().put("hide-git", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { hide(); } });
        restoreBounds(owner, initial); normalBounds = frame.getBounds();
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) { rememberNormalBounds(); }
            @Override public void componentResized(ComponentEvent event) { rememberNormalBounds(); }
        });
        workspace.restore(initial); dock.setText(docked ? "Undock" : "Dock");
    }
    public void project(Path project) { this.project = project; String name = project == null ? "No project" : project.getFileName().toString(); frame.setTitle("Git — " + name); title.setText("Git · " + name + " · read only"); title.setToolTipText(project == null ? "" : project.toString());
        title.setComponentPopupMenu(rootMenu()); workspace.project(project); }
    private JPopupMenu rootMenu() { Path captured = project; JPopupMenu menu = new JPopupMenu(); JMenuItem copy = new JMenuItem("Copy repository root"); copy.setEnabled(project != null); copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(captured.toString()), null)); menu.add(copy); return menu; }
    public void show() { if (closed) return; if (!visible) { visible = true; attach(); workspace.activate(); } else workspace.refresh(); if (!docked) { frame.setVisible(true); frame.toFront(); } }
    public void showCommit(dev.editor.git.ui.editor.CommitNavigation request) {
        if (closed || !java.util.Objects.equals(project, request.repository().root())) return;
        show(); workspace.inspectCommit(request);
    }
    public void showHistory() { if (!closed) { workspace.showHistory(); show(); } }
    public void showLocalChanges() { if (!closed) { workspace.showLocalChanges(); show(); } }
    public void hide() { if (!visible || closed) return; capture(); visible = false; workspace.deactivate(); if (docked) dockHost.accept(null); else frame.setVisible(false); save(); }
    public void setDocked(boolean value) {
        if (closed || docked == value) return;
        workspace.preserveViewState(() -> {
            capture(); if (docked) dockHost.accept(null); else { frame.getContentPane().remove(surface); frame.setVisible(false); }
            docked = value; dock.setText(docked ? "Undock" : "Dock"); if (visible) attach();
        }); save();
    }
    private void attach() { if (docked) { frame.getContentPane().remove(surface); dockHost.accept(surface); } else { dockHost.accept(null); frame.getContentPane().add(surface); frame.setVisible(true); frame.revalidate(); } }
    private void rememberNormalBounds() { if (!closed && frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds(); }
    private void capture() {
        Rectangle bounds = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0 ? frame.getBounds() : normalBounds;
        GitViewState host = new GitViewState(bounds.x, bounds.y, bounds.width, bounds.height, (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0, docked, saved.vertical(), saved.references(), saved.details(), saved.files(), saved.showReferences(), saved.showDetails(), saved.unified(), saved.ignoreWhitespace(), saved.history(), saved.currentBranch());
        saved = workspace.capture(host);
    }
    private CompletableFuture<Void> save() {
        capture(); GitViewState state = saved;
        writes = writes.handle((ignored, failure) -> null).thenRunAsync(() -> { try { store.save(state); } catch (java.io.IOException failure) { throw new CompletionException(failure); } }, workers);
        return writes;
    }
    public CompletableFuture<Void> flush() { return save(); }
    private void restoreBounds(Window owner, GitViewState state) {
        Rectangle desired = new Rectangle(state.x(), state.y(), state.width(), state.height());
        GraphicsConfiguration screen = owner.getGraphicsConfiguration();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) for (GraphicsConfiguration configuration : device.getConfigurations()) if (configuration.getBounds().intersects(desired)) screen = configuration;
        Rectangle area = new Rectangle(screen.getBounds()); Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen); area.x += insets.left; area.y += insets.top; area.width -= insets.left + insets.right; area.height -= insets.top + insets.bottom;
        int width = Math.min(state.width(), area.width), height = Math.min(state.height(), area.height);
        int x = state.x() == -1 ? area.x + (area.width - width) / 2 : Math.clamp(state.x(), area.x, area.x + area.width - width);
        int y = state.y() == -1 ? area.y + (area.height - height) / 2 : Math.clamp(state.y(), area.y, area.y + area.height - height);
        frame.setBounds(x, y, width, height); if (state.maximized()) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
    }
    public void preserveViewState(Runnable update) { workspace.preserveViewState(update); }
    public void applySettings(TextEditorSettings settings, EditorTheme theme) {
        SwingUtilities.updateComponentTreeUI(frame); UiTypography.apply(surface, theme); workspace.applySettings(settings, theme); frame.repaint();
    }
    public GitWorkspacePanel workspace() { return workspace; }
    public JFrame frame() { return frame; }
    @Override public void close() { if (closed) return; closed = true; workspace.close(); if (docked) dockHost.accept(null); frame.dispose(); }
}
