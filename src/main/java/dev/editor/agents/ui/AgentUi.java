package dev.editor.agents.ui;

import javax.swing.*;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/** Small presentation helpers shared by the agent views. No service or protocol behavior. */
final class AgentUi {
    private AgentUi() { }
    /** Vertical transcript/form content always tracks its viewport width, including long unbroken tokens. */
    static class Column extends JPanel implements Scrollable {
        Column() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(320, 240); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(20, visible.height - 20); }
    }
    /** Measure bounded content without changing preferred-size properties during an ancestor layout pass. */
    static JScrollPane boundedScroll(JComponent content, int minimum, int maximum, int padding) {
        JScrollPane scroll = new JScrollPane(content) {
            @Override public Dimension getPreferredSize() {
                return new Dimension(0, Math.clamp(content.getPreferredSize().height + padding, minimum, maximum));
            }
        };
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(20);
        return scroll;
    }
    static Color errorColor(EditorTheme theme) { Color error = UIManager.getColor("Component.error.borderColor"); return error == null ? theme.accent() : error; }
    static JLabel label(String text) {
        JLabel label = new JLabel(text); label.putClientProperty("html.disable", true); return label;
    }
    static JButton button(String text, String name, Runnable action) {
        JButton button = new JButton(text); button.setName(name); button.putClientProperty("html.disable", true);
        button.addActionListener(event -> action.run()); return button;
    }
    static JButton quietButton(String text, String name, Runnable action) {
        JButton button = button(text, name, action); button.putClientProperty("JButton.buttonType", "toolBarButton"); return button;
    }
    static JPanel row() { JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); panel.setOpaque(false); return panel; }
    static JTextArea text(String name) {
        JTextArea text = new JTextArea(); text.setName(name); text.setEditable(false); text.setLineWrap(true);
        text.setWrapStyleWord(true); text.setOpaque(false); text.setBorder(null);
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE); return text;
    }
    static boolean copy(String text) {
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); return true; }
        catch (IllegalStateException | HeadlessException unavailable) { return false; }
    }
}
