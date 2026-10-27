package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.diff.ui.SnapshotDiffDialog;
import java.util.concurrent.ExecutorService;
import dev.rilvune.platform.theme.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** Incremental conversation rendering and scroll ownership, independent of runtime orchestration. */
final class AgentConversationView extends JPanel {
    private final AgentUi.Column entries = new AgentUi.Column();
    private final JScrollPane scroll = new JScrollPane(entries);
    private final AgentScrollSupport scrolling = new AgentScrollSupport(this::userScrolled);
    private final JButton latest = AgentUi.quietButton("Latest ↓", "agents.latest", this::followLatest);
    private final JPanel welcome = new JPanel();
    private final JLabel welcomeTitle = AgentUi.label("What would you like to work on?");
    private final Map<Long, AgentMessageView> views = new LinkedHashMap<>();
    private final Map<Long, AgentActivityView> activity = new LinkedHashMap<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private final Map<String, Boolean> followingByChat = new HashMap<>();
    private String chatId = "";
    private final JLabel truncated = AgentUi.label("Older display entries are omitted; saved history is retained.");
    private final AgentTurnProgressView progress;
    private EditorTheme theme;
    private final ExecutorService workers;
    private final List<SnapshotDiffDialog> dialogs;
    private boolean following = true, updatingScroll;
    private long layoutVersion, userScrollVersion, navigationVersion, latestTurn;
    private List<AgentMessage> displayed = List.of();
    private AgentProgress displayedProgress;
    private boolean displayedWorking;
    private int scrollMaximum, scrollExtent;

    AgentConversationView(EditorTheme theme, Consumer<String> suggest, ExecutorService workers, List<SnapshotDiffDialog> dialogs) {
        super(new BorderLayout()); this.workers = workers; this.dialogs = dialogs; setName("agents.conversation");
        progress = new AgentTurnProgressView(theme);
        scroll.setName("agents.conversation.scroll"); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20); add(scroll);
        scrolling.watch(scroll);
        JPanel navigation = AgentUi.row(); navigation.add(latest); add(navigation, BorderLayout.SOUTH); latest.setVisible(false);
        welcome.setLayout(new BoxLayout(welcome, BoxLayout.Y_AXIS)); welcome.setBorder(BorderFactory.createEmptyBorder(32, 16, 24, 16));
        welcome.add(welcomeTitle); welcome.add(Box.createVerticalStrut(10));
        JTextArea description = AgentUi.text("agents.welcome"); description.setText("Ask a question, investigate a bug, or plan a change together.");
        description.setAlignmentX(LEFT_ALIGNMENT); welcome.add(description); welcome.add(Box.createVerticalStrut(18));
        for (String suggestion : List.of("Explain this project", "Review recent changes", "Help me investigate a bug")) {
            JButton button = AgentUi.button(suggestion, "agents.suggestion", () -> suggest.accept(suggestion));
            button.setAlignmentX(LEFT_ALIGNMENT); welcome.add(button); welcome.add(Box.createVerticalStrut(8));
        }
        entries.add(welcome);
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            boolean resized = scrollMaximum != bar.getMaximum() || scrollExtent != bar.getVisibleAmount();
            scrollMaximum = bar.getMaximum(); scrollExtent = bar.getVisibleAmount();
            if (updatingScroll) return;
            // A growing document or a resized pending-work area is not a user scrolling away.
            if (resized) { if (following) scrollLatestAfterLayout(); }
            else { following = atBottom(); latest.setVisible(!following); }
        });
        entries.addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent event) { entries.revalidate(); } });
        applyTheme(theme);
    }
    void render(AgentSnapshot snapshot) {
        if (!chatId.equals(snapshot.chatId())) {
            navigationVersion++;
            positions.put(chatId, scroll.getVerticalScrollBar().getValue()); followingByChat.put(chatId, following);
            chatId = snapshot.chatId(); views.clear(); activity.clear(); entries.removeAll(); displayed = null;
            following = followingByChat.getOrDefault(chatId, true);
        }
        Set<String> pendingTools = new HashSet<>();
        if (snapshot.phase() == AgentSnapshot.Phase.BUSY && !snapshot.cancelling()) snapshot.permissions().forEach(permission -> pendingTools.add(permission.tool().id()));
        boolean working = snapshot.phase() == AgentSnapshot.Phase.BUSY || snapshot.phase() == AgentSnapshot.Phase.STOPPING;
        AgentProgress currentProgress = snapshot.details().progress();
        if (snapshot.messages().equals(displayed) && displayedWorking == working && currentProgress.equals(displayedProgress)) { activity.values().forEach(view -> view.pendingTools(pendingTools)); return; }
        displayedWorking = working; displayedProgress = currentProgress;
        boolean changedChat = displayed == null; displayed = snapshot.messages();
        boolean keepFollowing = following; int position = changedChat ? positions.getOrDefault(chatId, 0) : scroll.getVerticalScrollBar().getValue();
        Component anchor = changedChat || keepFollowing ? null : scrollAnchor(position);
        int anchorInset = anchor == null ? 0 : position - anchor.getY();
        long layout = ++layoutVersion, interaction = userScrollVersion; updatingScroll = true;
        Map<Long, List<AgentMessage>> groups = new LinkedHashMap<>();
        List<Component> desired = new ArrayList<>(); Set<Long> messageIds = new HashSet<>(); long turn = 0;
        if (snapshot.historyTruncated()) desired.add(truncated);
        if (displayed.isEmpty()) desired.add(welcome);
        for (AgentMessage message : displayed) {
            if (message.role() == AgentMessage.Role.USER) turn = message.id();
            boolean reasoning = message instanceof AgentActivityMessage update && update.kind() == AgentActivityMessage.Kind.REASONING;
            if (turn == 0 && message.technical()) turn = message.id();
            // Both views project the same reasoning entry; only the trace counts it as an action.
            if (!message.technical() || reasoning) {
                messageIds.add(message.id()); AgentMessageView view = views.get(message.id());
                if (view == null) { view = new AgentMessageView(message, snapshot.provider().name(), theme, workers, dialogs); views.put(message.id(), view); }
                else view.render(message);
                long messageTurn = turn;
                view.onExpansion((target, change) -> expand(messageTurn, target, change));
                desired.add(view);
            }
            if (message.technical()) {
                if (!groups.containsKey(turn)) {
                    groups.put(turn, new ArrayList<>());
                    long groupTurn = turn;
                    desired.add(activity.computeIfAbsent(turn, ignored -> new AgentActivityView(theme, workers, dialogs,
                            (target, change) -> expand(groupTurn, target, change))));
                }
                groups.get(turn).add(message);
            }
        }
        latestTurn = turn;
        groups.forEach((id, tools) -> { activity.get(id).pendingTools(pendingTools); activity.get(id).render(tools); });
        progress.render(currentProgress);
        if (working) desired.add(progress);
        views.keySet().removeIf(id -> !messageIds.contains(id)); activity.keySet().removeIf(id -> !groups.containsKey(id));
        for (Component child : entries.getComponents()) if (!desired.contains(child)) entries.remove(child);
        for (int index = 0; index < desired.size(); index++) {
            Component child = desired.get(index); if (child.getParent() != entries) entries.add(child);
            if (entries.getComponent(index) != child) entries.setComponentZOrder(child, index);
        }
        entries.revalidate(); entries.repaint();
        restoreAfterLayout(layout, interaction, keepFollowing, position, anchor, anchorInset);
    }
    private void restoreAfterLayout(long layout, long interaction, boolean keepFollowing, int position, Component anchor, int anchorInset) {
        AgentScrollSupport.afterLayout(this, () -> layout == layoutVersion, () -> {
            if (interaction == userScrollVersion) {
                JScrollBar bar = scroll.getVerticalScrollBar();
                int restored = anchor != null && anchor.getParent() == entries ? anchor.getY() + anchorInset : position;
                bar.setValue(keepFollowing ? bar.getMaximum() : restored);
            }
            updatingScroll = false; following = interaction == userScrollVersion ? keepFollowing : atBottom(); latest.setVisible(!following);
        });
    }
    private Component scrollAnchor(int position) {
        for (Component entry : entries.getComponents()) if (entry.getY() <= position && entry.getY() + entry.getHeight() > position) return entry;
        return null;
    }
    private void expand(long turn, JComponent target, Runnable change) {
        boolean keepFollowing = following && turn == latestTurn;
        String sourceChat = chatId; long navigation = ++navigationVersion, interaction = ++userScrollVersion;
        scrolling.watchAncestors(target, this); long nestedInteraction = scrolling.interaction();
        following = keepFollowing; updatingScroll = true; layoutVersion++;
        change.run(); scrolling.watchContents(target);
        AgentScrollSupport.afterLayout(this, () -> navigation == navigationVersion && sourceChat.equals(chatId), () -> {
            if (interaction == userScrollVersion && nestedInteraction == scrolling.interaction()) {
                layoutVersion++; // An explicit disclosure takes precedence over queued transcript anchoring.
                AgentScrollSupport.reveal(target, this, keepFollowing);
                if (keepFollowing) scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                userScrollVersion++; following = keepFollowing;
            } else following = atBottom();
            updatingScroll = false; latest.setVisible(!following);
        });
    }
    private void userScrolled(JScrollPane pane) {
        userScrollVersion++;
        if (pane == scroll) return;
        navigationVersion++; layoutVersion++; updatingScroll = false;
        following = false; latest.setVisible(true);
    }
    void revealTool(String id) {
        long navigation = ++navigationVersion;
        for (AgentActivityView group : activity.values()) {
            AgentMessageView target = group.revealTool(id);
            if (target != null) {
                following = false; userScrollVersion++; latest.setVisible(true); String sourceChat = chatId;
                updatingScroll = true; layoutVersion++;
                scrolling.watchAncestors(target, this); scrolling.watchContents(target);
                long interaction = userScrollVersion;
                AgentScrollSupport.afterLayout(this, () -> navigation == navigationVersion && sourceChat.equals(chatId) && SwingUtilities.isDescendingFrom(target, entries), () -> {
                    if (interaction == userScrollVersion) {
                        // A render queued during navigation must not restore the old reading position.
                        layoutVersion++;
                        AgentScrollSupport.reveal(target, this, false);
                        userScrollVersion++; following = false;
                    } else following = atBottom();
                    updatingScroll = false; latest.setVisible(!following);
                });
                return;
            }
        }
    }
    void followLatest() {
        navigationVersion++; following = true; userScrollVersion++; latest.setVisible(false);
        scrollLatestAfterLayout();
    }
    private void scrollLatestAfterLayout() {
        long layout = ++layoutVersion; updatingScroll = true;
        restoreAfterLayout(layout, userScrollVersion, true, 0, null, 0);
    }
    private boolean atBottom() { JScrollBar bar = scroll.getVerticalScrollBar(); return bar.getMaximum() - bar.getValue() - bar.getVisibleAmount() < 28; }
    void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface()); entries.setBackground(theme.surface()); welcome.setBackground(theme.surface());
        scroll.getViewport().setBackground(theme.surface()); UiTypography.apply(this, theme);
        progress.applyTheme(theme);
        UiTypography.interfaceFont(welcomeTitle, theme, Font.BOLD, 1); views.values().forEach(view -> view.applyTheme(theme)); activity.values().forEach(view -> view.applyTheme(theme));
    }
}
