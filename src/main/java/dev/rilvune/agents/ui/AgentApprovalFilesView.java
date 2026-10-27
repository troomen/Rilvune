package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.AgentToolCall;
import dev.rilvune.agents.domain.content.AgentContent;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.Glyph;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** One supplied path per group. Every diff retains its own review action; no filesystem access or patch composition. */
final class AgentApprovalFilesView extends AgentUi.Column {
    private final Consumer<AgentContent.Diff> review;
    private EditorTheme theme;
    private AgentToolCall tool;

    AgentApprovalFilesView(EditorTheme theme, Consumer<AgentContent.Diff> review) {
        this.theme = theme; this.review = review; setName("agents.permission.files"); setOpaque(false); setAlignmentX(LEFT_ALIGNMENT);
    }
    void render(AgentToolCall tool) {
        this.tool = tool; removeAll();
        Map<String, List<AgentContent.Diff>> paths = new LinkedHashMap<>();
        for (AgentContent block : tool.content()) if (block instanceof AgentContent.Diff diff)
            paths.computeIfAbsent(diff.path(), ignored -> new ArrayList<>()).add(diff);
        tool.locations().forEach(location -> paths.computeIfAbsent(location.path(), ignored -> new ArrayList<>()));
        boolean fileAction = Set.of("edit", "delete", "move").contains(tool.kind());
        setVisible(!paths.isEmpty() || fileAction);
        if (!paths.isEmpty()) {
            JLabel heading = AgentUi.label("Files · " + paths.size()); heading.setName("agents.permission.files.heading");
            heading.setAlignmentX(LEFT_ALIGNMENT); heading.setForeground(theme.muted()); UiTypography.interfaceFont(heading, theme, Font.PLAIN, -1); add(heading);
            paths.forEach((path, diffs) -> add(file(path, diffs)));
        } else if (fileAction) {
            JLabel unavailable = unavailable(); unavailable.setText("Diff unavailable · no change preview supplied"); add(unavailable);
        }
        UiTypography.apply(this, theme); revalidate(); repaint();
    }
    private JPanel file(String path, List<AgentContent.Diff> diffs) {
        JPanel row = new JPanel(new BorderLayout(0, 6)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override public Dimension getPreferredSize() { return new Dimension(0, super.getPreferredSize().height); }
        };
        row.setOpaque(false); row.setAlignmentX(LEFT_ALIGNMENT); row.setName("agents.permission.file"); row.putClientProperty("agents.permission.file.path", path);
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border()), BorderFactory.createEmptyBorder(8, 0, 8, 0)));
        JPanel labels = new JPanel(new BorderLayout(0, 2)); labels.setOpaque(false); labels.setMinimumSize(new Dimension(0, 0));
        String normalized = path.replace('\\', '/'); int slash = normalized.lastIndexOf('/');
        JLabel name = AgentUi.label(path.isBlank() ? "Unspecified file" : normalized.substring(slash + 1));
        name.setName("agents.permission.file.name"); name.setIcon(new Glyph(Glyph.Kind.FILE, theme.muted(), 14)); name.setForeground(theme.text()); UiTypography.interfaceFont(name, theme, Font.BOLD, 0);
        String locations = String.join(", ", tool.locations().stream().filter(location -> location.path().equals(path) && location.line() != null)
                .map(location -> path + ":" + (location.line() + 1)).distinct().toList());
        name.setToolTipText(locations.isEmpty() ? "File: " + path : "Location: " + locations); labels.add(name, BorderLayout.NORTH);
        if (slash >= 0) {
            JLabel directory = AgentUi.label(normalized.substring(0, slash + 1)); directory.setName("agents.permission.file.directory");
            directory.setToolTipText("File: " + path); directory.setForeground(theme.muted()); UiTypography.interfaceFont(directory, theme, Font.PLAIN, -1); labels.add(directory);
        }
        row.add(labels, BorderLayout.NORTH);
        JPanel reviews = new JPanel(new GridLayout(0, 1, 0, 3)); reviews.setOpaque(false);
        if (diffs.isEmpty()) reviews.add(unavailable());
        for (int index = 0; index < diffs.size(); index++) {
            AgentContent.Diff diff = diffs.get(index);
            String action = diffs.size() == 1 ? "Review changes" : "Review change " + (index + 1);
            JButton button = AgentUi.quietButton(action, "agents.diff.review", () -> review.accept(diff));
            button.setHorizontalAlignment(SwingConstants.LEFT); button.setIcon(new Glyph(Glyph.Kind.CHEVRON_RIGHT, theme.muted(), 12));
            button.setToolTipText(action + " · " + path); button.getAccessibleContext().setAccessibleName(action + " for " + path); reviews.add(button);
        }
        row.add(reviews); return row;
    }
    private JLabel unavailable() {
        JLabel label = AgentUi.label("Diff unavailable"); label.setName("agents.permission.diff.unavailable"); label.setForeground(theme.muted());
        label.setToolTipText("The agent supplied no diff for this file. Inspect the action summary and Details before deciding."); return label;
    }
    void applyTheme(EditorTheme theme) { this.theme = theme; if (tool != null) render(tool); }
}
