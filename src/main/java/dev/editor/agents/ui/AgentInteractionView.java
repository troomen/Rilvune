package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.diff.ui.SnapshotDiffDialog;
import dev.editor.platform.theme.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One pending interaction at a time. Queue navigation never submits a decision or discards an edited form. */
final class AgentInteractionView extends JPanel {
    private final JLabel heading = AgentUi.label("");
    private final JButton previous, next;
    private final JPanel body = new JPanel(new BorderLayout());
    private final Map<String, AgentPermissionView> approvals = new HashMap<>();
    private final BiConsumer<String, String> choose;
    private final Consumer<String> inspect;
    private final ExecutorService workers;
    private final List<SnapshotDiffDialog> dialogs;
    private final Map<String, AgentQuestionView> forms = new HashMap<>();
    private final BiConsumer<String, AgentQuestionAnswer> answer;
    private AgentSnapshot snapshot;
    private List<String> queue = List.of();
    private String selected = "";
    private EditorTheme theme;

    AgentInteractionView(EditorTheme theme, BiConsumer<String, String> choose,
                         BiConsumer<String, AgentQuestionAnswer> answer, Consumer<String> inspect,
                         ExecutorService workers, List<SnapshotDiffDialog> dialogs) {
        super(new BorderLayout(0, 8)); this.answer = answer; this.choose = choose; this.inspect = inspect; this.workers = workers; this.dialogs = dialogs; setName("agents.interactions");
        previous = AgentUi.quietButton("←", "agents.interaction.previous", () -> move(-1));
        next = AgentUi.quietButton("→", "agents.interaction.next", () -> move(1));
        previous.setToolTipText("Previous pending request"); next.setToolTipText("Next pending request");
        previous.getAccessibleContext().setAccessibleName("Previous pending request"); next.getAccessibleContext().setAccessibleName("Next pending request");
        heading.setName("agents.interaction.heading");
        JPanel header = new JPanel(new BorderLayout(6, 0)); header.setOpaque(false); header.add(heading);
        JPanel navigation = AgentUi.row(); navigation.add(previous); navigation.add(next); header.add(navigation, BorderLayout.EAST);
        body.setOpaque(false); add(header, BorderLayout.NORTH); add(body);
        applyTheme(theme); setVisible(false);
    }
    boolean render(AgentSnapshot snapshot) {
        this.snapshot = snapshot;
        List<String> pending = new ArrayList<>();
        if (snapshot.phase() == AgentSnapshot.Phase.BUSY && !snapshot.cancelling()) {
            snapshot.permissions().forEach(request -> pending.add("permission:" + request.id()));
            snapshot.details().questions().forEach(question -> pending.add("question:" + question.id()));
        }
        int oldIndex = Math.max(0, queue.indexOf(selected)); queue = List.copyOf(pending);
        if (!queue.contains(selected)) selected = queue.isEmpty() ? "" : queue.get(Math.min(oldIndex, queue.size() - 1));
        forms.keySet().removeIf(id -> !queue.contains("question:" + id));
        approvals.keySet().removeIf(id -> !queue.contains("permission:" + id));
        setVisible(!queue.isEmpty()); showSelected(); return !queue.isEmpty();
    }
    private void move(int delta) {
        int index = queue.indexOf(selected) + delta;
        if (index >= 0 && index < queue.size()) { selected = queue.get(index); showSelected(); }
    }
    private void showSelected() {
        if (queue.isEmpty()) { body.removeAll(); return; }
        int index = queue.indexOf(selected); previous.setEnabled(index > 0); next.setEnabled(index + 1 < queue.size());
        previous.setVisible(queue.size() > 1); next.setVisible(queue.size() > 1);
        AgentPermission request = snapshot.permissions().stream().filter(value -> selected.equals("permission:" + value.id())).findFirst().orElse(null);
        JComponent view;
        if (request != null) {
            heading.setText("Approval required" + (queue.size() > 1 ? " · " + (index + 1) + " of " + queue.size() : ""));
            boolean hasActivity = snapshot.messages().stream().anyMatch(message -> message instanceof AgentToolMessage tool && tool.call().id().equals(request.tool().id()));
            AgentPermissionView permission = approvals.computeIfAbsent(request.id(), ignored -> new AgentPermissionView(theme, choose, inspect, workers, dialogs));
            permission.render(request, snapshot.provider().name(), hasActivity); view = permission;
        } else {
            heading.setText("Answer needed" + (queue.size() > 1 ? " · " + (index + 1) + " of " + queue.size() : ""));
            AgentQuestion question = snapshot.details().questions().stream().filter(value -> selected.equals("question:" + value.id())).findFirst().orElseThrow();
            AgentQuestionView form = forms.computeIfAbsent(question.id(), ignored -> new AgentQuestionView(theme, answer));
            form.render(List.of(question), true); view = form;
        }
        if (view.getParent() != body) { body.removeAll(); body.add(view); }
        revalidate(); repaint();
    }
    void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.raised());
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, theme.accent()), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        UiTypography.apply(this, theme); UiTypography.interfaceFont(heading, theme, Font.BOLD, 0); heading.setForeground(theme.accent());
        approvals.values().forEach(permission -> permission.applyTheme(theme)); forms.values().forEach(form -> form.applyTheme(theme));
    }
    @Override public Dimension getMinimumSize() { return new Dimension(0, super.getMinimumSize().height); }
}
