package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.AgentSnapshot;
import dev.rilvune.platform.theme.EditorTheme;
import javax.swing.*;
import java.awt.*;

/** Conversation and pending work share the available space; neither can grow over the composer. */
final class AgentChatView extends JSplitPane {
    private final AgentConversationView conversation;
    private final AgentInteractionView interactions;
    private boolean pending;

    AgentChatView(AgentConversationView conversation, AgentInteractionView interactions) {
        super(VERTICAL_SPLIT); this.conversation = conversation; this.interactions = interactions;
        setName("agents.chat.split"); setTopComponent(conversation); setBottomComponent(null);
        setBorder(BorderFactory.createEmptyBorder()); setDividerSize(0); setResizeWeight(0.5); setContinuousLayout(true);
        conversation.setMinimumSize(new Dimension(0, 60));
    }
    void render(AgentSnapshot snapshot) {
        conversation.render(snapshot);
        boolean hasPending = interactions.render(snapshot);
        if (pending == hasPending) return;
        pending = hasPending; setBottomComponent(pending ? interactions : null); setDividerSize(pending ? 6 : 0);
        if (pending) AgentScrollSupport.afterLayout(this, () -> pending, () -> {
            int available = Math.max(0, getHeight() - getDividerSize());
            int requestHeight = Math.min(interactions.getPreferredSize().height, (int)(available * 0.68));
            setDividerLocation(Math.max(conversation.getMinimumSize().height, available - requestHeight));
        });
        revalidate(); repaint();
    }
    void applyTheme(EditorTheme theme) {
        setBackground(theme.surface()); conversation.applyTheme(theme); interactions.applyTheme(theme);
    }
    @Override public void doLayout() {
        super.doLayout();
        if (!pending) return;
        int maximum = Math.max(0, getHeight() - getInsets().bottom - getDividerSize() - interactions.getMinimumSize().height);
        int minimum = Math.min(maximum, getInsets().top + conversation.getMinimumSize().height);
        int location = Math.clamp(getDividerLocation(), minimum, maximum);
        if (location != getDividerLocation()) { setDividerLocation(location); super.doLayout(); }
    }
}
