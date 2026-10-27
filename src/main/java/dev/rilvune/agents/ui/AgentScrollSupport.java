package dev.rilvune.agents.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Geometry shared by disclosures and targeted navigation. Scroll policy remains with each viewport's owner. */
final class AgentScrollSupport {
    private final Set<JScrollPane> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private final Consumer<JScrollPane> interacted;
    private long interaction;

    AgentScrollSupport() { this(ignored -> { }); }
    AgentScrollSupport(Consumer<JScrollPane> interacted) { this.interacted = interacted; }

    long interaction() { return interaction; }
    void watch(JScrollPane pane) {
        if (!watched.add(pane)) return;
        pane.addMouseWheelListener(event -> interacted(pane));
        pane.getVerticalScrollBar().addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent event) { interacted(pane); } });
        KeyAdapter keys = new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { interacted(pane); } };
        pane.addKeyListener(keys); pane.getVerticalScrollBar().addKeyListener(keys);
    }
    private void interacted(JScrollPane pane) { interaction++; interacted.accept(pane); }
    void watchContents(Container target) {
        if (target instanceof JScrollPane pane) watch(pane);
        for (Component child : target.getComponents()) if (child instanceof Container container) watchContents(container);
    }
    void watchAncestors(JComponent target, JComponent boundary) {
        for (Container parent = target.getParent(); parent != null && parent != boundary; parent = parent.getParent())
            if (parent instanceof JScrollPane pane) watch(pane);
    }
    static void afterLayout(JComponent owner, BooleanSupplier current, Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (!current.getAsBoolean()) return;
            Window window = SwingUtilities.getWindowAncestor(owner); if (window != null) window.validate();
            if (current.getAsBoolean()) action.run();
        });
    }
    static void reveal(JComponent target, JComponent boundary, boolean end) {
        if (!target.isShowing() || !SwingUtilities.isDescendingFrom(target, boundary)) return;
        int height = Math.min(target.getHeight(), Math.max(40, target.getFontMetrics(target.getFont()).getHeight() * 3));
        Rectangle region = new Rectangle(0, end ? Math.max(0, target.getHeight() - height) : 0, target.getWidth(), height);
        // JViewport consumes scroll requests. Reveal in every enclosing viewport, inside out.
        for (Container parent = target.getParent(); parent != null && parent != boundary; parent = parent.getParent())
            if (parent instanceof JViewport viewport && viewport.getView() instanceof JComponent view)
                view.scrollRectToVisible(SwingUtilities.convertRectangle(target, region, view));
    }
}
