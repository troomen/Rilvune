package dev.editor.platform.ui;

import dev.editor.platform.theme.EditorTheme;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

public final class SurfacePanel extends JPanel {
    private final EditorTheme theme;

    public SurfacePanel(EditorTheme theme) {
        super(new BorderLayout());
        this.theme = theme;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(theme.surface());
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g.dispose();
        super.paintComponent(graphics);
    }
}
