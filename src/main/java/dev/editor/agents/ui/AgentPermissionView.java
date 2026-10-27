package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.agents.domain.content.AgentContent;
import dev.editor.diff.ui.SnapshotDiffDialog;
import dev.editor.platform.theme.*;
import dev.editor.platform.ui.Glyph;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Compact file review and optional technical details; decisions remain outside the viewport. */
final class AgentPermissionView extends JPanel {
    private final AgentUi.Column content = new AgentUi.Column();
    private final JScrollPane scroll = AgentUi.boundedScroll(content, 90, 320, 4);
    private final JLabel title = AgentUi.label("");
    private final JTextArea context = AgentUi.text("agents.permission.context");
    private final JTextArea details = AgentUi.text("agents.permission.details");
    private final JTextArea summary = AgentUi.text("agents.permission.summary");
    private final AgentScrollSupport scrolling = new AgentScrollSupport();
    private long disclosureVersion;
    private final AgentContentView rich;
    private final AgentApprovalFilesView files;
    private final JToggleButton detailsToggle = new JToggleButton("Details");
    private final AgentUi.Column expandedDetails = new AgentUi.Column();
    private List<AgentContent> detailContent = List.of();
    private final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 5));
    private final JButton showActivity;
    private final BiConsumer<String, String> choose;
    private AgentPermission displayed;
    private EditorTheme theme;

    AgentPermissionView(EditorTheme theme, BiConsumer<String, String> choose, Consumer<String> inspect,
                        ExecutorService workers, List<SnapshotDiffDialog> dialogs) {
        super(new BorderLayout(0, 8)); this.choose = choose; this.theme = theme; setName("agents.permissions");
        title.setName("agents.permission.title"); add(title, BorderLayout.NORTH);
        rich = new AgentContentView(theme, workers, dialogs);
        files = new AgentApprovalFilesView(theme, rich::review);
        detailsToggle.setName("agents.permission.details.toggle"); detailsToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        detailsToggle.setHorizontalAlignment(SwingConstants.LEFT); detailsToggle.setToolTipText("Show input, output and additional content");
        detailsToggle.getAccessibleContext().setAccessibleName("Approval details");
        detailsToggle.addActionListener(event -> expand(expandedDetails, this::showDetails));
        expandedDetails.setOpaque(false); expandedDetails.setVisible(false); expandedDetails.add(details); expandedDetails.add(rich);
        scroll.setMinimumSize(new Dimension(0, 90));
        for (JComponent view : List.of(context, summary, files, detailsToggle, expandedDetails)) {
            view.setAlignmentX(LEFT_ALIGNMENT); content.add(view); content.add(Box.createVerticalStrut(8));
        }
        showActivity = AgentUi.quietButton("Show in activity", "agents.permission.activity", () -> {
            if (displayed != null) inspect.accept(displayed.tool().id());
        });
        JPanel footer = new JPanel(new BorderLayout(0, 6)); footer.setOpaque(false);
        JPanel navigation = AgentUi.row(); navigation.add(showActivity); footer.add(navigation, BorderLayout.NORTH);
        actions.setOpaque(false); footer.add(actions); add(scroll); add(footer, BorderLayout.SOUTH);
        scrolling.watch(scroll);
        applyTheme(theme);
    }
    void render(AgentPermission request, String assistant, boolean hasActivity) {
        showActivity.setEnabled(hasActivity);
        if (!request.equals(displayed)) {
            disclosureVersion++;
            boolean newRequest = displayed == null || !displayed.id().equals(request.id());
            displayed = request; title.setText(request.title());
            AgentToolCall tool = request.tool();
            context.setText("Requested by " + (tool.origin().child() ? tool.origin().label() : assistant));
            title.setToolTipText("Action: " + request.title());
            files.render(tool);
            AgentApprovalPresentation presentation = AgentApprovalPresentation.from(request);
            summary.setText(presentation.summary());
            String text = presentation.details(); details.setText(text); details.setVisible(!text.isBlank());
            detailContent = tool.content().stream().filter(block -> !(block instanceof AgentContent.Diff)).toList();
            detailsToggle.setVisible(!text.isBlank() || !detailContent.isEmpty());
            if (newRequest) detailsToggle.setSelected(false);
            showDetails(); actions.removeAll();
            for (AgentPermission.Option option : request.options()) {
                JButton button = AgentUi.button(option.name(), "agents.permission." + option.kind(), () -> choose.accept(request.id(), option.id()));
                button.setToolTipText(option.name() + " · " + request.title()); actions.add(button);
            }
            UiTypography.apply(this, theme); UiTypography.interfaceFont(title, theme, Font.BOLD, 0);
            if (newRequest) scroll.getVerticalScrollBar().setValue(0); revalidate();
        }
    }
    private void expand(JComponent target, Runnable change) {
        long version = ++disclosureVersion, interaction = scrolling.interaction();
        scrolling.watchAncestors(target, this); change.run(); scrolling.watchContents(target);
        AgentScrollSupport.afterLayout(this, () -> version == disclosureVersion && isShowing(), () -> {
            if (interaction == scrolling.interaction()) AgentScrollSupport.reveal(target, this, false);
        });
    }
    private void showDetails() {
        boolean expanded = detailsToggle.isVisible() && detailsToggle.isSelected();
        expandedDetails.setVisible(expanded); rich.render(expanded ? detailContent : List.of());
        detailsToggle.setIcon(new Glyph(expanded ? Glyph.Kind.CHEVRON : Glyph.Kind.CHEVRON_RIGHT, theme.muted(), 12));
        revalidate(); repaint();
    }
    void applyTheme(EditorTheme theme) {
        this.theme = theme; setOpaque(false); content.setBackground(theme.raised());
        scroll.getViewport().setBackground(theme.raised()); UiTypography.apply(this, theme);
        UiTypography.interfaceFont(title, theme, Font.BOLD, 0); title.setForeground(theme.text());
        context.setForeground(theme.muted()); summary.setForeground(theme.text()); details.setForeground(theme.text()); rich.applyTheme(theme); files.applyTheme(theme); showDetails();
    }
}
