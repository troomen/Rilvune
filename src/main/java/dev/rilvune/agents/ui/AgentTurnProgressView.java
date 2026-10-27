package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.AgentProgress;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.UiTypography;
import javax.swing.*;
import java.awt.*;

/** Ephemeral end-of-turn status, never a saved message or a claim that the turn has finished. */
final class AgentTurnProgressView extends JPanel {
    private final JLabel title = AgentUi.label("");
    private AgentProgress progress = AgentProgress.idle();
    private EditorTheme theme;

    AgentTurnProgressView(EditorTheme theme) {
        super(new BorderLayout());
        setName("agents.turn.progress"); setAlignmentX(LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        title.setName("agents.turn.progress.title");
        add(title);
        applyTheme(theme);
    }

    void render(AgentProgress progress) {
        this.progress = progress;
        title.setText(switch (progress.kind()) {
            case APPROVAL -> "Approval needed";
            case QUESTION -> "Waiting for your answer";
            case STOPPING -> "Stopping…";
            case THINKING -> "Thinking…";
            case RESPONDING -> "Responding…";
            case RETRYING -> "Retrying…";
            case COMPACTING -> "Compacting…";
            default -> "Working…";
        });
        title.setIcon(AgentStatusIcon.progress(progress, theme));
        title.setToolTipText(progress.detail());
        getAccessibleContext().setAccessibleName(title.getText());
        getAccessibleContext().setAccessibleDescription(progress.detail());
    }

    void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface());
        UiTypography.apply(this, theme); UiTypography.interfaceFont(title, theme, Font.BOLD, 0);
        title.setForeground(theme.accent()); render(progress);
    }

    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override public Dimension getPreferredSize() { return new Dimension(0, super.getPreferredSize().height); }
}
