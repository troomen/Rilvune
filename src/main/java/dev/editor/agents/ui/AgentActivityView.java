package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.diff.ui.SnapshotDiffDialog;
import dev.editor.platform.theme.*;
import dev.editor.platform.ui.Glyph;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Lazy per-turn technical trace, with distinct nested child-agent groups and bounded visible rows. */
final class AgentActivityView extends JPanel {
    private final JToggleButton toggle = new JToggleButton();
    private final AgentUi.Column rows = new AgentUi.Column();
    private final JScrollPane scroll = AgentUi.boundedScroll(rows, 65, 300, 6);
    private int visible = 50;
    private final JButton earlier = AgentUi.quietButton("", "agents.activity.earlier", () -> { visible += 50; updateRows(); });
    private final Map<Long, AgentMessageView> views = new HashMap<>();
    private final Map<String, AgentActivityView> children = new HashMap<>();
    private final ExecutorService workers;
    private final List<SnapshotDiffDialog> dialogs;
    private final AgentOrigin origin;
    private final AgentExpansionHandler expansion;
    private List<AgentMessage> messages = List.of();
    private Set<String> pendingTools = Set.of();
    private EditorTheme theme;
    AgentActivityView(EditorTheme theme, ExecutorService workers, List<SnapshotDiffDialog> dialogs) { this(theme, workers, dialogs, AgentExpansionHandler.DIRECT); }
    AgentActivityView(EditorTheme theme, ExecutorService workers, List<SnapshotDiffDialog> dialogs, AgentExpansionHandler expansion) { this(theme, workers, dialogs, AgentOrigin.MAIN, expansion); }
    private AgentActivityView(EditorTheme theme, ExecutorService workers, List<SnapshotDiffDialog> dialogs, AgentOrigin origin, AgentExpansionHandler expansion) {
        super(new BorderLayout(0, 5)); this.theme = theme; this.workers = workers; this.dialogs = dialogs; this.origin = origin; this.expansion = expansion; setAlignmentX(LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        toggle.setName(origin.child() ? "agents.activity.child" : "agents.activity"); toggle.putClientProperty("html.disable", true); toggle.putClientProperty("JButton.buttonType", "toolBarButton");
        toggle.setContentAreaFilled(false); toggle.setBorderPainted(false); toggle.setOpaque(false); toggle.setMargin(new Insets(2, 3, 2, 3));
        toggle.setHorizontalAlignment(SwingConstants.LEFT); toggle.setToolTipText("Expand reasoning, tools, plans and agent activity");
        toggle.addActionListener(event -> expansion.change(rows, () -> {
            arrow(); updateRows(); revalidate();
        }));
        JPanel heading = new JPanel(new BorderLayout(0, 5)); heading.setOpaque(false); heading.add(toggle, BorderLayout.NORTH);
        add(heading, BorderLayout.NORTH);
        add(scroll); scroll.setVisible(false); applyTheme(theme);
    }
    void render(List<AgentMessage> messages) {
        if (this.messages.equals(messages)) return; this.messages = List.copyOf(messages); summary();
        if (toggle.isSelected()) updateRows();
    }
    void pendingTools(Set<String> ids) {
        if (pendingTools.equals(ids)) return; pendingTools = Set.copyOf(ids); summary();
        if (toggle.isSelected()) updateRows();
    }
    private void summary() {
        Map<AgentToolCall.Status, Integer> counts = new EnumMap<>(AgentToolCall.Status.class);
        int tools = 0, reasoning = 0;
        for (AgentMessage message : messages) {
            if (message instanceof AgentToolMessage tool) { counts.merge(tool.call().status(), 1, Integer::sum); tools++; }
            else if (message instanceof AgentActivityMessage activity && activity.kind() == AgentActivityMessage.Kind.REASONING) {
                counts.merge(activity.status(), 1, Integer::sum); reasoning++;
            }
        }
        int actions = counts.values().stream().mapToInt(Integer::intValue).sum();
        int completed = counts.getOrDefault(AgentToolCall.Status.COMPLETED, 0), running = counts.getOrDefault(AgentToolCall.Status.RUNNING, 0);
        int waiting = counts.getOrDefault(AgentToolCall.Status.PENDING, 0), failed = counts.getOrDefault(AgentToolCall.Status.FAILED, 0), stopped = counts.getOrDefault(AgentToolCall.Status.CANCELLED, 0);
        boolean approval = messages.stream().anyMatch(message -> message instanceof AgentToolMessage tool && pendingTools.contains(tool.call().id()));
        String count, state, details;
        boolean attention;
        if (actions > 0) {
            boolean active = running + waiting > 0;
            count = active ? completed + "/" + actions + " done"
                    : completed > 0 ? completed + " completed"
                    : failed > 0 ? failed + " failed" : stopped + " stopped";
            // One priority status keeps the header compact alongside reasoning and approval cards.
            state = approval ? "Approval needed" : failed > 0 && (active || completed > 0) ? failed + " failed"
                    : running > 0 ? "Running…" : waiting > 0 ? "Waiting…"
                    : stopped > 0 && (completed > 0 || failed > 0) ? stopped + " stopped" : "";
            details = actions + (actions == 1 ? " known action: " : " known actions: ") + completed + " completed, " + running + " running, " + waiting + " waiting, " + failed + " failed, " + stopped + " stopped."
                    + " Includes " + tools + (tools == 1 ? " tool action and " : " tool actions and ")
                    + reasoning + (reasoning == 1 ? " reasoning step." : " reasoning steps.")
                    + (approval ? " Approval required." : "") + " The total can grow as new actions are discovered.";
            attention = active || approval;
        } else {
            boolean active = messages.stream().anyMatch(message -> status(message) == AgentToolCall.Status.RUNNING || status(message) == AgentToolCall.Status.PENDING);
            count = messages.size() + (messages.size() == 1 ? " update" : " updates");
            state = active ? "Working…" : "";
            details = "Expand plans and agent activity. No tool actions recorded.";
            attention = active;
        }
        toggle.setText((origin.child() ? origin.label() : "Activity") + " · " + count + (state.isEmpty() ? "" : " · " + state));
        toggle.setToolTipText(details); toggle.getAccessibleContext().setAccessibleDescription(details);
        toggle.setForeground(approval ? theme.accent() : failed > 0 ? AgentUi.errorColor(theme) : attention ? theme.accent() : theme.muted());
    }
    private static AgentToolCall.Status status(AgentMessage message) {
        return message instanceof AgentToolMessage tool ? tool.call().status() : message instanceof AgentActivityMessage activity ? activity.status() : AgentToolCall.Status.COMPLETED;
    }
    private void updateRows() {
        scroll.setVisible(toggle.isSelected()); if (!toggle.isSelected()) return;
        int from = Math.max(0, messages.size() - visible); List<AgentMessage> window = messages.subList(from, messages.size());
        List<Component> desired = new ArrayList<>();
        if (from > 0) { earlier.setText("Show " + Math.min(50, from) + " earlier entries"); desired.add(earlier); }
        Map<String, List<AgentMessage>> groups = new LinkedHashMap<>(); Set<Long> shown = new HashSet<>();
        Map<String, AgentOrigin> origins = new HashMap<>(); window.stream().map(AgentMessage::origin).filter(AgentOrigin::child).forEach(value -> origins.put(value.id(), value));
        for (AgentMessage message : window) {
            if (origin.depth() < 8 && message.origin().child() && message.origin().depth() > origin.depth() && !message.origin().id().equals(origin.id())) {
                AgentOrigin group = message.origin(); Set<String> visited = new HashSet<>();
                while (!group.parentId().equals(origin.id()) && origins.containsKey(group.parentId()) && visited.add(group.id())) group = origins.get(group.parentId());
                String id = group.id(); AgentOrigin descriptor = group;
                if (!groups.containsKey(id)) { groups.put(id, new ArrayList<>()); desired.add(children.computeIfAbsent(id, ignored -> new AgentActivityView(theme, workers, dialogs, descriptor, expansion))); }
                groups.get(id).add(message);
            } else {
                shown.add(message.id()); AgentMessageView view = views.computeIfAbsent(message.id(), ignored -> new AgentMessageView(message, "", theme, workers, dialogs)); view.onExpansion(expansion); view.render(message);
                view.awaitingApproval(message instanceof AgentToolMessage tool && pendingTools.contains(tool.call().id())); desired.add(view);
            }
        }
        groups.forEach((id, entries) -> { children.get(id).pendingTools(pendingTools); children.get(id).render(entries); }); children.keySet().removeIf(id -> !groups.containsKey(id)); views.keySet().removeIf(id -> !shown.contains(id));
        for (Component component : rows.getComponents()) if (!desired.contains(component)) rows.remove(component);
        for (int index = 0; index < desired.size(); index++) { Component component = desired.get(index); if (component.getParent() != rows) rows.add(component); rows.setComponentZOrder(component, index); }
        rows.revalidate(); revalidate(); repaint();
    }
    AgentMessageView revealTool(String id) {
        int index = -1;
        for (int i = 0; i < messages.size(); i++) if (messages.get(i) instanceof AgentToolMessage tool && tool.call().id().equals(id)) { index = i; break; }
        if (index < 0) return null;
        visible = Math.max(visible, messages.size() - index); toggle.setSelected(true); arrow(); updateRows();
        AgentMessageView view = views.get(messages.get(index).id());
        if (view != null) { view.revealDetails(); return view; }
        for (AgentActivityView child : children.values()) { view = child.revealTool(id); if (view != null) return view; }
        return null;
    }
    private void arrow() { toggle.setIcon(new Glyph(toggle.isSelected() ? Glyph.Kind.CHEVRON : Glyph.Kind.CHEVRON_RIGHT, theme.muted(), 12)); }
    void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface()); rows.setBackground(theme.surface()); scroll.getViewport().setBackground(theme.surface());
        UiTypography.apply(this, theme); summary(); arrow(); views.values().forEach(view -> view.applyTheme(theme)); children.values().forEach(view -> view.applyTheme(theme));
    }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override public Dimension getPreferredSize() { return new Dimension(0, super.getPreferredSize().height); }
}
