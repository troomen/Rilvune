package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.platform.theme.*;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.function.Consumer;
import static dev.editor.agents.domain.AgentSnapshot.Phase.*;

/** Installation, sign-in and recoverable failures are explicit presentation states. */
final class AgentSetupView extends JPanel {
    private final JLabel title = AgentUi.label("");
    private final JTextArea description = AgentUi.text("agents.setup.description"), feedback = AgentUi.text("agents.login.feedback");
    private final JProgressBar progress = new JProgressBar();
    private final JButton primary, open, copy, cancel;
    private final JTextField link = new JTextField(20);
    private final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 6));
    private AgentLogin login;
    private boolean opening;

    AgentSetupView(EditorTheme theme, Runnable connect, Runnable stop, Consumer<URI> browse) {
        super(new BorderLayout(0, 12)); setName("agents.setupView"); setBorder(BorderFactory.createEmptyBorder(24, 16, 24, 16));
        JPanel prose = new JPanel(new BorderLayout(0, 10)); prose.setOpaque(false); prose.add(title, BorderLayout.NORTH); prose.add(description); add(prose, BorderLayout.NORTH);
        JPanel body = new JPanel(); body.setOpaque(false); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS)); add(body);
        progress.setIndeterminate(true); progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 3)); progress.setAlignmentX(LEFT_ALIGNMENT); body.add(progress); body.add(Box.createVerticalStrut(8));
        actions.setOpaque(false); actions.setAlignmentX(LEFT_ALIGNMENT);
        primary = AgentUi.button("", "agents.setup", connect);
        open = AgentUi.button("Open browser", "agents.login.open", () -> { if (login != null) browse.accept(login.uri()); });
        copy = AgentUi.button("Copy sign-in link", "agents.login.copy", () -> {
            if (login == null) return;
            if (AgentUi.copy(login.uri().toString())) feedback.setText("Link copied. Paste it into your browser to continue.");
            else { link.setVisible(true); link.selectAll(); feedback.setText("Select the link below and copy it into your browser."); }
            revalidate();
        });
        cancel = AgentUi.quietButton("Cancel sign-in", "agents.login.cancel", stop);
        actions.add(primary); actions.add(open); actions.add(copy); actions.add(cancel); body.add(actions);
        link.setName("agents.loginUrl"); link.setEditable(false); link.setAlignmentX(LEFT_ALIGNMENT); link.setVisible(false);
        feedback.setAlignmentX(LEFT_ALIGNMENT); body.add(Box.createVerticalStrut(8)); body.add(feedback); body.add(link);
        feedback.setRows(2); description.setRows(3);
        applyTheme(theme);
    }
    void browserOpening() { opening = true; open.setEnabled(false); feedback.setText("Opening your browser…"); revalidate(); }
    void browserFinished(boolean success) {
        opening = false; open.setEnabled(login != null);
        feedback.setText(success ? "Browser opened. Complete sign-in there." : "Browser could not open. Copy the sign-in link to continue."); revalidate();
    }
    void focusPrimary() { if (primary.isShowing()) primary.requestFocusInWindow(); }
    void render(AgentSnapshot snapshot, boolean compact) {
        setBorder(BorderFactory.createEmptyBorder(compact ? 8 : 24, compact ? 10 : 16, compact ? 12 : 24, compact ? 10 : 16));
        boolean authorizing = snapshot.phase() == AUTHORIZING, waiting = snapshot.phase() == STARTING || snapshot.phase() == INSTALLING || snapshot.phase() == STOPPING;
        if (!java.util.Objects.equals(login, snapshot.login())) { opening = false; feedback.setText(""); link.setText(""); link.setVisible(false); }
        login = snapshot.login();
        if (login != null) link.setText(login.uri().toString());
        setVisible(snapshot.phase() != READY && snapshot.phase() != BUSY && (!compact || snapshot.phase() == AgentSnapshot.Phase.ERROR || snapshot.phase() == AUTH_REQUIRED || authorizing));
        description.setRows(compact ? 2 : 3);
        primary.setVisible(snapshot.phase() == NOT_INSTALLED || snapshot.phase() == AUTH_REQUIRED || snapshot.phase() == AgentSnapshot.Phase.ERROR);
        primary.setText(snapshot.phase() == NOT_INSTALLED ? "Install & connect" : snapshot.phase() == AgentSnapshot.Phase.ERROR ? "Try again" : "Sign in with " + snapshot.provider().accountName());
        java.util.List<JButton> available = new java.util.ArrayList<>();
        if (primary.isVisible()) available.add(primary);
        if (authorizing) { available.add(open); available.add(copy); available.add(cancel); }
        if (!java.util.Arrays.equals(actions.getComponents(), available.toArray())) {
            actions.removeAll(); available.forEach(actions::add);
        }
        feedback.setVisible(authorizing);

        open.setEnabled(login != null && !opening); copy.setEnabled(login != null); progress.setVisible(waiting || authorizing);
        title.setText(switch (snapshot.phase()) {
            case NO_PROJECT -> "Open a project to get started";
            case NOT_INSTALLED -> "Your project, with an assistant";
            case INSTALLING -> "Setting up your assistant";
            case AUTH_REQUIRED -> "Connect your " + snapshot.provider().accountName() + " account";
            case AUTHORIZING -> login == null ? "Preparing secure sign-in…" : "Finish signing in";
            case ERROR -> compact ? "Connection interrupted" : "Unable to connect";
            case STOPPING -> "Stopping the assistant…";
            case CLOSED -> "Assistant closed";
            default -> "Connecting…";
        });
        description.setText(switch (snapshot.phase()) {
            case NOT_INSTALLED -> "Install " + snapshot.engine().name() + ", then sign in with your account in the browser.";
            case AUTH_REQUIRED -> "Continue with your account in the browser. Your connection is saved for this editor profile.";
            case AUTHORIZING -> login == null ? "Getting your browser sign-in ready."
                    : "Approve access in your browser. This panel updates automatically. If no browser opened, copy the sign-in link.";
            case NO_PROJECT -> "The assistant works with the files in your open project.";
            default -> snapshot.status();
        });
    }
    void applyTheme(EditorTheme theme) {
        setBackground(theme.surface()); UiTypography.apply(this, theme);
        UiTypography.interfaceFont(title, theme, Font.BOLD, 2); title.setForeground(theme.text());
        description.setForeground(theme.muted()); feedback.setForeground(theme.muted());
    }
}
