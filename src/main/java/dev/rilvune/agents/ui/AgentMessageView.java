package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.domain.content.AgentContent;
import dev.rilvune.diff.ui.SnapshotDiffDialog;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Stable selectable entry. Rich result rendering is shared with permission previews. */
final class AgentMessageView extends JPanel {
    private final JLabel title = AgentUi.label(""), state = AgentUi.label("");
    private final JButton copy;
    private final JToggleButton disclosure = new JToggleButton();
    private final AgentTextView body;
    private final AgentContentView rich;
    private final JPanel expanded = new JPanel(new BorderLayout(0, 4));
    private final boolean technical;
    private AgentExpansionHandler expansion = AgentExpansionHandler.DIRECT;
    private boolean awaitingApproval;
    private AgentMessage message;
    private EditorTheme theme;
    private final String assistant;
    AgentMessageView(AgentMessage message, String assistant, EditorTheme theme, ExecutorService workers, List<SnapshotDiffDialog> dialogs) {
        super(new BorderLayout(0, 6)); this.technical = message instanceof AgentToolMessage || message instanceof AgentActivityMessage; this.theme = theme; this.assistant = assistant;
        setName("agents.message." + message.id()); setAlignmentX(LEFT_ALIGNMENT);
        title.setName("agents.message.title"); state.setName("agents.message.status");
        body = new AgentTextView(theme); body.setName("agents.message.text"); rich = new AgentContentView(theme, workers, dialogs);
        copy = UiComponents.iconButton(theme, Glyph.Kind.COPY, "Copy message", () -> AgentUi.copy(this.message.copyText())); copy.setName("agents.message.copy");
        JPanel header = new JPanel(new BorderLayout(6, 0)); header.setOpaque(false); header.add(title);
        JPanel actions = AgentUi.row();
        if (technical) {
            if (message instanceof AgentActivityMessage activity && activity.kind() == AgentActivityMessage.Kind.REASONING) actions.add(state);
            else header.add(state, BorderLayout.SOUTH);
            actions.add(disclosure); disclosure.setPreferredSize(new Dimension(24, 24));
            disclosure.setToolTipText("Show or hide details"); disclosure.getAccessibleContext().setAccessibleName("Activity details");
            disclosure.setName("agents.tool.details"); disclosure.putClientProperty("JButton.buttonType", "toolBarButton");
            expanded.setOpaque(false);
            if (message instanceof AgentActivityMessage activity && activity.kind() == AgentActivityMessage.Kind.REASONING) { body.setName("agents.thinking.text"); expanded.add(body); }
            else expanded.add(rich);
            add(expanded); expanded.setVisible(false);
            disclosure.addActionListener(event -> expansion.change(expanded, () -> {
                expanded.setVisible(disclosure.isSelected()); arrow(); revalidate(); repaint();
            }));
        } else if (message instanceof AgentContentMessage) add(rich);
        else {
            JPanel messageBody = new JPanel(new BorderLayout(0, 6)); messageBody.setOpaque(false);
            messageBody.add(body); messageBody.add(rich, BorderLayout.SOUTH); rich.setVisible(false); add(messageBody);
        }
        actions.add(copy); header.add(actions, BorderLayout.EAST); add(header, BorderLayout.NORTH);
        render(message); applyTheme(theme);
    }
    void render(AgentMessage next) {
        if (next.equals(message)) return; message = next;
        if (next instanceof AgentToolMessage tool) {
            AgentToolCall call = tool.call(); title.setText(call.title()); title.setToolTipText("Tool: " + call.title());
            title.setIcon(new Glyph(switch (call.kind()) { case "read", "edit", "delete", "move" -> Glyph.Kind.FILE; case "search", "fetch" -> Glyph.Kind.SEARCH; case "execute" -> Glyph.Kind.TERMINAL; case "think" -> Glyph.Kind.SPARK; default -> Glyph.Kind.INFO; }, theme.muted(), 14)); status(call.status());
            List<AgentContent> blocks = new ArrayList<>();
            if (!call.locations().isEmpty()) blocks.add(new AgentContent.Text(String.join("\n", call.locations().stream().map(location -> location.path() + (location.line() == null ? "" : ":" + (location.line() + 1))).toList())));
            if (!call.input().isBlank()) blocks.add(new AgentContent.Text("Input\n" + call.input()));
            blocks.addAll(call.content());
            if (!call.output().isBlank()) blocks.add(new AgentContent.Text((call.content().isEmpty() ? "Output\n" : "Metadata\n") + call.output()));
            if (blocks.isEmpty()) blocks.add(new AgentContent.Text("No result content was supplied by the agent.")); rich.render(blocks);
        } else if (next instanceof AgentActivityMessage activity) {
            title.setText(activity.title()); status(activity.status()); if (activity.kind() == AgentActivityMessage.Kind.PLAN) { state.setText("Updated"); state.setIcon(new Glyph(Glyph.Kind.INFO, theme.muted(), 14)); }
            String text = activity.text().isBlank() ? statusText(activity.status()) : activity.text();
            if (activity.kind() == AgentActivityMessage.Kind.REASONING) body.showText(text, false);
            else rich.render(List.of(new AgentContent.Text(text)));
            if (activity.kind() == AgentActivityMessage.Kind.REASONING) {
                title.setText(activity.origin().child() ? activity.origin().label() + " · Reasoning" : "Reasoning");
                title.setIcon(new Glyph(Glyph.Kind.SPARK, theme.muted(), 14));
                title.setToolTipText(activity.title());
                disclosure.setName("agents.thinking.details"); disclosure.setToolTipText("Show or hide reasoning"); disclosure.getAccessibleContext().setAccessibleName("Reasoning details");
            }
        } else {
            title.setText(switch (next.role()) { case USER -> "You"; case ASSISTANT -> assistant.isEmpty() ? "Agent reply" : assistant; case SYSTEM -> "Notice"; case TOOL -> "Tool"; });
            if (next instanceof AgentContentMessage content) rich.render(List.of(content.content()));
            else if (technical) rich.render(List.of(new AgentContent.Text(next.text())));
            else {
                body.showText(next.text(), next.role() == AgentMessage.Role.ASSISTANT); body.setVisible(!next.text().isBlank());
                List<AgentContent> blocks = next instanceof AgentTextMessage text ? text.content() : List.of();
                rich.render(blocks); rich.setVisible(!blocks.isEmpty());
            }
        }
        revalidate();
    }
    private void status(AgentToolCall.Status status) { state.setForeground(status == AgentToolCall.Status.FAILED ? AgentUi.errorColor(theme) : theme.muted()); state.setText(statusText(status)); state.setIcon(AgentStatusIcon.operation(status, theme)); state.setToolTipText(statusText(status)); }
    private static String statusText(AgentToolCall.Status status) { return switch (status) { case PENDING -> "Waiting"; case RUNNING -> "Running"; case COMPLETED -> "Done"; case FAILED -> "Failed"; case CANCELLED -> "Stopped"; }; }
    private void arrow() { disclosure.setIcon(new Glyph(disclosure.isSelected() ? Glyph.Kind.CHEVRON : Glyph.Kind.CHEVRON_RIGHT, theme.muted(), 12)); }
    void revealDetails() {
        if (technical && !disclosure.isSelected()) { disclosure.setSelected(true); expanded.setVisible(true); arrow(); revalidate(); }
    }
    void onExpansion(AgentExpansionHandler expansion) { this.expansion = expansion; }
    void awaitingApproval(boolean pending) {
        awaitingApproval = pending;
        if (message instanceof AgentToolMessage tool) {
            status(tool.call().status());
            if (pending) { state.setText("Approval"); state.setToolTipText("Approval required for this action"); state.setForeground(theme.accent()); state.setIcon(new Glyph(Glyph.Kind.QUESTION, theme.accent(), 14)); }
        }
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, technical ? 2 : 0, 1, 0, pending ? theme.accent() : theme.border()), BorderFactory.createEmptyBorder(technical ? 6 : 10, 10, technical ? 6 : 10, 10)));
    }
    void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(message.role() == AgentMessage.Role.USER ? theme.raised() : theme.surface());
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, technical ? 2 : 0, 1, 0, theme.border()), BorderFactory.createEmptyBorder(technical ? 6 : 10, 10, technical ? 6 : 10, 10)));
        UiTypography.apply(this, theme); UiTypography.interfaceFont(title, theme, Font.BOLD, 0); title.setForeground(technical ? theme.text() : theme.muted());
        state.setForeground(theme.muted()); UiTypography.interfaceFont(state, theme, Font.PLAIN, -1); arrow();
        copy.setIcon(new Glyph(Glyph.Kind.COPY, theme.muted(), 14)); body.applyTheme(theme); rich.applyTheme(theme);
        AgentMessage saved = message; message = null; render(saved); awaitingApproval(awaitingApproval);
    }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override public Dimension getPreferredSize() {
        return new Dimension(0, super.getPreferredSize().height);
    }
}
