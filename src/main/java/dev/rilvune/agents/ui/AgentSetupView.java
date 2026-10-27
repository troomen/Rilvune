package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.*;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;

/** Installation, sign-in and recoverable failures are explicit presentation states. */
final class AgentSetupView extends JPanel implements Scrollable {
    private final JTextArea title = AgentUi.text("agents.setup.title");
    private final JTextArea description = AgentUi.text("agents.setup.description"), feedback = AgentUi.text("agents.login.feedback");
    private final JProgressBar progress = new JProgressBar();
    private final JButton primary, open, copy, cancel;
    private final JTextField link = new JTextField(20);
    private final StackLayout stack = new StackLayout();
    private AgentLogin login;
    private boolean opening;

    AgentSetupView(EditorTheme theme, Runnable connect, Runnable stop, Consumer<URI> browse) {
        setLayout(stack); setName("agents.setupView"); setBorder(BorderFactory.createEmptyBorder(20, 16, 20, 16));
        title.setFocusable(false); description.setFocusable(false); feedback.setFocusable(false);
        addRow(title, 0); addRow(description, 10);
        progress.setName("agents.setup.progress"); progress.setIndeterminate(true); addRow(progress, 16);
        primary = AgentUi.button("", "agents.setup", connect);
        open = AgentUi.button("Open browser", "agents.login.open", () -> { if (login != null) browse.accept(login.uri()); });
        copy = AgentUi.quietButton("Copy sign-in link", "agents.login.copy", () -> {
            if (login == null) return;
            if (AgentUi.copy(login.uri().toString())) feedback("Link copied. Paste it into your browser to continue.");
            else { link.setVisible(true); link.selectAll(); feedback("Select the link below and copy it into your browser."); }
            revalidate();
        });
        cancel = AgentUi.quietButton("Cancel sign-in", "agents.login.cancel", stop);
        addRow(primary, 20); addRow(open, 16); addRow(copy, 6); addRow(cancel, 2);
        link.setName("agents.loginUrl"); link.setEditable(false); link.setVisible(false);
        addRow(feedback, 12); addRow(link, 8);
        applyTheme(theme);
    }
    void browserOpening() { opening = true; open.setEnabled(false); feedback("Opening your browser…"); }
    void browserFinished(boolean success) {
        opening = false; open.setEnabled(login != null);
        feedback(success ? "Browser opened. Complete sign-in there." : "Browser could not open. Copy the sign-in link to continue.");
    }
    private void feedback(String message) { feedback.setText(message); feedback.setVisible(!message.isEmpty()); revalidate(); repaint(); }
    void focusPrimary() { if (primary.isShowing() && primary.isEnabled()) primary.requestFocusInWindow(); }
    void render(AgentSnapshot snapshot, boolean compact) {
        setBorder(BorderFactory.createEmptyBorder(compact ? 12 : 20, compact ? 12 : 16, compact ? 16 : 20, compact ? 12 : 16));
        boolean authorizing = snapshot.phase() == AUTHORIZING, waiting = snapshot.phase() == STARTING || snapshot.phase() == INSTALLING || snapshot.phase() == STOPPING;
        if (!java.util.Objects.equals(login, snapshot.login())) { opening = false; feedback.setText(""); link.setText(""); link.setVisible(false); }
        login = snapshot.login();
        if (login != null) link.setText(login.uri().toString());
        setVisible(snapshot.phase() != READY && snapshot.phase() != BUSY && (!compact || snapshot.phase() == UNSUPPORTED || snapshot.phase() == AgentSnapshot.Phase.ERROR || snapshot.phase() == AUTH_REQUIRED || authorizing));
        primary.setVisible(snapshot.phase() == UNSUPPORTED || snapshot.phase() == NOT_INSTALLED || snapshot.phase() == AUTH_REQUIRED || snapshot.phase() == AgentSnapshot.Phase.ERROR);
        primary.setEnabled(snapshot.phase() != UNSUPPORTED);
        primary.setText(switch (snapshot.phase()) {
            case UNSUPPORTED -> "Download unavailable";
            case NOT_INSTALLED -> "Install & connect";
            case ERROR -> "Try again";
            default -> "Sign in";
        });
        open.setVisible(authorizing); copy.setVisible(authorizing); cancel.setVisible(authorizing);
        feedback.setVisible(authorizing && !feedback.getText().isEmpty());

        open.setEnabled(login != null && !opening); copy.setEnabled(login != null); progress.setVisible(waiting || authorizing);
        title.setText(switch (snapshot.phase()) {
            case NO_PROJECT -> "Open a project";
            case UNSUPPORTED -> "Unsupported platform";
            case NOT_INSTALLED -> "Set up your assistant";
            case INSTALLING -> "Setting up your assistant";
            case AUTH_REQUIRED -> "Connect " + snapshot.provider().accountName();
            case AUTHORIZING -> login == null ? "Preparing sign-in…" : "Continue in your browser";
            case ERROR -> compact ? "Connection interrupted" : "Unable to connect";
            case STOPPING -> "Stopping the assistant…";
            case CLOSED -> "Assistant closed";
            default -> "Connecting…";
        });
        description.setText(switch (snapshot.phase()) {
            case NOT_INSTALLED -> "Download the assistant, then sign in to chat about your project.";
            case AUTH_REQUIRED -> "Sign in to chat about your project. Your connection is saved for this profile.";
            case AUTHORIZING -> login == null ? "Getting your browser sign-in ready."
                    : "Approve the connection in your browser. This panel updates automatically.";
            case NO_PROJECT -> "The assistant works with the files in your open project.";
            default -> snapshot.status();
        });
        revalidate(); repaint();
    }
    void applyTheme(EditorTheme theme) {
        setBackground(theme.surface()); UiTypography.apply(this, theme);
        UiTypography.interfaceFont(title, theme, Font.BOLD, 2); title.setForeground(theme.text());
        description.setForeground(theme.text()); feedback.setForeground(theme.muted());
        for (JButton button : new JButton[]{primary, open}) {
            button.setBackground(theme.accent()); button.setForeground(Color.WHITE);
        }
    }
    private void addRow(JComponent component, int gap) { stack.gaps.put(component, gap); add(component); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(320, 260); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(20, visible.height - 20); }

    /** Measure wrapped text at its allocated width, without changing component bounds during measurement. */
    private static final class StackLayout implements LayoutManager {
        private final Map<Component, Integer> gaps = new IdentityHashMap<>();
        @Override public void addLayoutComponent(String name, Component component) { }
        @Override public void removeLayoutComponent(Component component) { gaps.remove(component); }
        @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
        @Override public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int available = parent.getParent() instanceof JViewport viewport ? viewport.getExtentSize().width
                    : parent.getParent() != null ? parent.getParent().getWidth() : parent.getWidth();
            int width = Math.max(1, (available > 0 ? available : 320) - insets.left - insets.right);
            int height = insets.top + insets.bottom;
            for (Component component : parent.getComponents()) if (component.isVisible())
                height += gaps.getOrDefault(component, 0) + height(component, width);
            return new Dimension(0, height);
        }
        @Override public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets(); int width = Math.max(0, parent.getWidth() - insets.left - insets.right), y = insets.top;
            for (Component component : parent.getComponents()) if (component.isVisible()) {
                y += gaps.getOrDefault(component, 0); int height = height(component, width);
                component.setBounds(insets.left, y, width, height); y += height;
            }
        }
        private static int height(Component component, int width) {
            if (component instanceof JTextArea text) {
                Insets insets = text.getInsets(); var root = text.getUI().getRootView(text);
                // Swing reserves one horizontal pixel for the caret, even on read-only text.
                root.setSize(Math.max(1, width - insets.left - insets.right - 1), Integer.MAX_VALUE);
                return Math.max(text.getFontMetrics(text.getFont()).getHeight(), (int)Math.ceil(root.getPreferredSpan(javax.swing.text.View.Y_AXIS))) + insets.top + insets.bottom;
            }
            if (component instanceof JProgressBar) return 3;
            return Math.max(component instanceof AbstractButton ? 32 : 0, component.getPreferredSize().height);
        }
    }
}
