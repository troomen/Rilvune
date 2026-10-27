package dev.rilvune.agents.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/** Responsive toolbar: advertised choices wrap as a group; send/stop always retain their own space. */
final class AgentComposerToolbar extends JPanel {
    private static final int GAP = 5;
    private final List<? extends Component> choices;
    private final Component utilities, actions;
    AgentComposerToolbar(List<? extends Component> choices, Component utilities, Component actions) {
        super(null); this.choices = choices; this.utilities = utilities; this.actions = actions; setOpaque(false);
        choices.forEach(this::add); add(utilities); add(actions);
        addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent event) { revalidate(); } });
    }
    private int choicesWidth() { return choices.stream().filter(Component::isVisible).mapToInt(component -> component.getPreferredSize().width + GAP).sum(); }
    private int actionHeight() { return Math.max(utilities.getPreferredSize().height, actions.getPreferredSize().height); }
    private int rowHeight() { return Math.max(actionHeight(), choices.stream().filter(Component::isVisible).mapToInt(component -> component.getPreferredSize().height).max().orElse(0)); }
    private int singleWidth() { return utilities.getPreferredSize().width + choicesWidth() + actions.getPreferredSize().width + GAP * 2; }
    private boolean wraps() { return getWidth() > 0 && singleWidth() > getWidth(); }
    private int choicesLayout(boolean place) {
        int x = 0, y = 0, height = 0, width = Math.max(1, getWidth());
        for (Component choice : choices) if (choice.isVisible()) {
            Dimension size = choice.getPreferredSize(); int itemWidth = Math.min(width, size.width);
            if (x > 0 && x + itemWidth > width) { x = 0; y += height + GAP; height = 0; }
            if (place) choice.setBounds(x, y, itemWidth, size.height);
            x += itemWidth + GAP; height = Math.max(height, size.height);
        }
        return y + height;
    }
    @Override public Dimension getPreferredSize() { return new Dimension(singleWidth(), wraps() ? choicesLayout(false) + GAP + actionHeight() : rowHeight()); }
    @Override public void doLayout() {
        int y = wraps() ? choicesLayout(true) + GAP : (rowHeight() - actionHeight()) / 2;
        utilities.setBounds(0, y, utilities.getPreferredSize().width, actionHeight());
        actions.setBounds(Math.max(0, getWidth() - actions.getPreferredSize().width), y, actions.getPreferredSize().width, actionHeight());
        if (!wraps()) { int x = utilities.getPreferredSize().width + GAP; for (Component choice : choices) if (choice.isVisible()) { Dimension size = choice.getPreferredSize(); choice.setBounds(x, (rowHeight() - size.height) / 2, size.width, size.height); x += size.width + GAP; } }
    }
}
