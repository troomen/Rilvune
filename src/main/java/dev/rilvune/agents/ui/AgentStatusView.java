package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.Glyph;
import javax.swing.*;
import java.awt.*;
import java.text.NumberFormat;

/** One clear live status and compact context indicator. Counters expand without competing with reply text. */
final class AgentStatusView extends JPanel {
    private final JLabel status = AgentUi.label("");
    private final JButton usage = AgentUi.quietButton("", "agents.usage", this::details);
    private AgentSnapshot snapshot;
    private EditorTheme theme;
    AgentStatusView(EditorTheme theme) {
        super(new BorderLayout(8, 0)); setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 10)); setOpaque(false);
        status.setName("agents.status"); add(status); add(usage, BorderLayout.EAST); applyTheme(theme);
        usage.getAccessibleContext().setAccessibleName("Context and token usage");
    }
    void render(AgentSnapshot snapshot) {
        this.snapshot = snapshot; AgentProgress progress = snapshot.details().progress();
        status.setForeground(progress.kind() == AgentProgress.Kind.ERROR ? AgentUi.errorColor(theme) : theme.muted()); status.setText(progress.detail()); status.setToolTipText("Status: " + progress.detail()); status.setIcon(AgentStatusIcon.progress(progress, theme));
        AgentUsage data = snapshot.details().usage();
        boolean reported = data.contextSize() > 0 || !data.tokens().isEmpty() || !data.currency().isEmpty();
        usage.setVisible(snapshot.session() != null || reported);
        usage.setText(data.contextSize() > 0 ? Math.round(data.contextUsed() * 100.0 / data.contextSize()) + "%" : reported ? "Usage" : "Usage —");
        usage.setToolTipText(reported ? "Context and token usage" : "Usage statistics have not been reported yet");
    }
    private void details() {
        if (snapshot == null) return; AgentUsage data = snapshot.details().usage(); StringBuilder text = new StringBuilder(); NumberFormat count = NumberFormat.getIntegerInstance();
        if (data.contextSize() > 0) text.append("Context · ").append(count.format(data.contextUsed())).append(" / ").append(count.format(data.contextSize())).append(" tokens\n");
        if (!data.currency().isEmpty()) text.append("Session cost · ").append(data.cost().stripTrailingZeros().toPlainString()).append(' ').append(data.currency()).append('\n');
        if (!data.tokens().isEmpty()) { text.append("\nLast turn\n"); data.tokens().forEach((key, value) -> text.append(key.replaceAll("([a-z])([A-Z])", "$1 $2")).append(" · ").append(count.format(value)).append('\n')); }
        if (text.isEmpty()) text.append("Usage statistics have not been reported\nfor this chat yet.");
        JTextArea body = AgentUi.text("agents.usage.details"); body.setText(text.toString()); body.setRows(Math.min(12, body.getLineCount())); body.setColumns(30);
        JPopupMenu popup = new JPopupMenu(); popup.setLayout(new BorderLayout()); popup.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); popup.add(body); UiTypography.apply(popup, theme); popup.show(usage, Math.min(0, usage.getWidth() - 300), usage.getHeight());
    }
    void applyTheme(EditorTheme theme) { this.theme = theme; UiTypography.apply(this, theme); status.setForeground(theme.muted()); usage.setIcon(new Glyph(Glyph.Kind.INFO, theme.muted(), 14)); if (snapshot != null) render(snapshot); }
}
