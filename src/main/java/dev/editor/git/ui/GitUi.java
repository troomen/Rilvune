package dev.editor.git.ui;

import javax.swing.*;
import java.awt.*;

/** Shared spacing and split behavior across Git inspection windows. */
final class GitUi {
    private GitUi() { }
    static JSplitPane split(int orientation, Component first, Component second, double fraction) {
        JSplitPane split = new JSplitPane(orientation, first, second); split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(5); split.setContinuousLayout(true); split.setResizeWeight(fraction);
        first.setMinimumSize(new Dimension(120, 60)); second.setMinimumSize(new Dimension(120, 60));
        SwingUtilities.invokeLater(() -> split.setDividerLocation(fraction)); return split;
    }
}
