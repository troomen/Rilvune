package dev.editor.platform.layout.ui;

import dev.editor.platform.layout.domain.Axis;
import dev.editor.platform.layout.domain.LayoutNode;
import dev.editor.platform.layout.domain.Panel;
import dev.editor.platform.layout.domain.Split;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/** The only translation from the platform-neutral layout tree to Swing containers. */
public final class SwingLayoutRenderer {
    private final PanelRegistry panels;
    private final List<Runnable> initialSizes = new ArrayList<>();

    public SwingLayoutRenderer(PanelRegistry panels) {
        this.panels = panels;
    }

    public JComponent render(LayoutNode node) {
        return switch (node) {
            case Panel panel -> panels.require(panel.id());
            case Split split -> {
                JSplitPane view = new JSplitPane(
                        split.axis() == Axis.HORIZONTAL ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT,
                        render(split.first()), render(split.second()));
                view.setContinuousLayout(true);
                view.setBorder(BorderFactory.createEmptyBorder());
                view.setDividerSize(5);
                view.setResizeWeight(split.fraction());
                view.setMinimumSize(new Dimension(0, 0));
                initialSizes.add(() -> { view.setDividerLocation(split.fraction()); view.doLayout(); });
                yield view;
            }
        };
    }

    /** Call after the hierarchy has its real window size. Parent splits are sized first. */
    public void applyInitialSizes() {
        for (int index = initialSizes.size() - 1; index >= 0; index--) initialSizes.get(index).run();
    }
}
