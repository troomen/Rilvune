package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.ui.Glyph;
import javax.swing.*;
import java.awt.*;

/** Animation has no timer ownership; one visible panel timer repaints all active icons. */
final class AgentStatusIcon implements Icon {
    private final boolean active;
    private final Glyph.Kind glyph;
    private final Color color;
    AgentStatusIcon(boolean active, Glyph.Kind glyph, Color color) { this.active = active; this.glyph = glyph; this.color = color; }
    static Icon progress(AgentProgress progress, EditorTheme theme) {
        Glyph.Kind kind = switch (progress.kind()) { case APPROVAL, QUESTION -> Glyph.Kind.QUESTION; case RETRYING -> Glyph.Kind.CLOCK; case ERROR -> Glyph.Kind.INFO; case STOPPING, STOPPED -> Glyph.Kind.STOP; default -> Glyph.Kind.CHECK; };
        return new AgentStatusIcon(progress.animated(), kind, progress.kind() == AgentProgress.Kind.ERROR ? AgentUi.errorColor(theme) : progress.animated() || progress.kind() == AgentProgress.Kind.APPROVAL || progress.kind() == AgentProgress.Kind.QUESTION ? theme.accent() : theme.muted());
    }
    static Icon operation(AgentToolCall.Status status, EditorTheme theme) {
        return new AgentStatusIcon(status == AgentToolCall.Status.RUNNING, switch (status) { case COMPLETED -> Glyph.Kind.CHECK; case FAILED -> Glyph.Kind.INFO; case CANCELLED -> Glyph.Kind.STOP; default -> Glyph.Kind.CLOCK; }, status == AgentToolCall.Status.FAILED ? AgentUi.errorColor(theme) : status == AgentToolCall.Status.RUNNING ? theme.accent() : theme.muted());
    }
    public int getIconWidth() { return 16; } public int getIconHeight() { return 16; }
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        if (!active) { new Glyph(glyph, color, 16).paintIcon(component, graphics, x, y); return; }
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int rotation = (int)((System.nanoTime() / 3_000_000) % 360);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45)); g.drawOval(x + 2, y + 2, 11, 11);
            g.setColor(color); g.drawArc(x + 2, y + 2, 11, 11, -rotation, 250);
        } finally { g.dispose(); }
    }
}
