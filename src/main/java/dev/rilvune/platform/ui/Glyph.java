package dev.rilvune.platform.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/** Small code-native icons; independent of icon fonts and platform glyph coverage. */
public record Glyph(Kind kind, Color color, int size) implements Icon {
    public enum Kind { FOLDER, FILE, MENU, CHAT, BRANCH, TERMINAL, SEARCH, REFRESH, CLOSE, MINUS, CHEVRON, COLLAPSE, PAPERCLIP, SEND, STOP, CHECK, CLOCK, INFO, QUESTION, COPY, CHEVRON_RIGHT, SPARK, PLUS }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.scale(size / 20.0, size / 20.0);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(component.isEnabled() ? color : new Color(color.getRed(), color.getGreen(), color.getBlue(), 90));
        switch (kind) {
            case FOLDER -> folder(g);
            case FILE -> file(g);
            case MENU -> { g.drawLine(3, 5, 17, 5); g.drawLine(3, 10, 17, 10); g.drawLine(3, 15, 17, 15); }
            case CHAT -> { g.drawRoundRect(2, 3, 16, 12, 4, 4); path(g, 5, 15, 5, 18, 9, 15); g.drawLine(6, 7, 14, 7); g.drawLine(6, 11, 11, 11); }
            case BRANCH -> { g.drawOval(3, 2, 4, 4); g.drawOval(3, 14, 4, 4); g.drawOval(13, 3, 4, 4); g.drawLine(5, 6, 5, 14); path(g, 5, 12, 10, 12, 15, 8, 15, 7); }
            case TERMINAL -> { g.drawRoundRect(2, 3, 16, 14, 2, 2); path(g, 5, 7, 8, 10, 5, 13); g.drawLine(11, 13, 15, 13); }
            case SEARCH -> { g.drawOval(3, 2, 11, 11); g.drawLine(13, 12, 18, 17); }
            case REFRESH -> { g.drawArc(3, 3, 14, 14, 40, 290); path(g, 12, 3, 17, 3, 17, 8); }
            case CLOSE -> { g.drawLine(5, 5, 15, 15); g.drawLine(5, 15, 15, 5); }
            case MINUS -> g.drawLine(4, 10, 16, 10);
            case CHEVRON -> path(g, 6, 8, 10, 12, 14, 8);
            case SEND -> { path(g, 3, 3, 18, 10, 3, 17, 6, 10, 3, 3); g.drawLine(6, 10, 13, 10); }
            case STOP -> g.fillRoundRect(5, 5, 10, 10, 2, 2);
            case CHECK -> path(g, 4, 10, 8, 14, 16, 5);
            case CLOCK -> { g.drawOval(3, 3, 14, 14); path(g, 10, 6, 10, 10, 13, 12); }
            case INFO -> { g.drawOval(3, 3, 14, 14); g.drawLine(10, 9, 10, 14); g.fillOval(9, 6, 2, 2); }
            case QUESTION -> { g.drawOval(3, 3, 14, 14); g.drawArc(7, 5, 6, 6, 0, 210); g.drawLine(10, 11, 10, 12); g.fillOval(9, 14, 2, 2); }
            case COPY -> { g.drawRoundRect(6, 6, 10, 11, 2, 2); path(g, 4, 13, 3, 13, 3, 3, 13, 3, 13, 4); }
            case CHEVRON_RIGHT -> path(g, 8, 5, 12, 10, 8, 15);
            case SPARK -> { path(g, 10, 2, 12, 8, 18, 10, 12, 12, 10, 18, 8, 12, 2, 10, 8, 8, 10, 2); }
            case PLUS -> { g.drawLine(4, 10, 16, 10); g.drawLine(10, 4, 10, 16); }
            case PAPERCLIP -> {
                Path2D.Double clip = new Path2D.Double(); clip.moveTo(7, 12); clip.lineTo(12, 7);
                clip.curveTo(14, 5, 16, 7, 14, 9); clip.lineTo(8, 15); clip.curveTo(5, 18, 1, 14, 4, 11);
                clip.lineTo(12, 3); clip.curveTo(16, -1, 21, 4, 17, 8); clip.lineTo(10, 15); g.draw(clip);
            }
            case COLLAPSE -> { path(g, 6, 7, 10, 3, 14, 7); path(g, 6, 13, 10, 17, 14, 13); }
        }
        g.dispose();
    }

    private static void folder(Graphics2D g) {
        Path2D.Double body = new Path2D.Double();
        body.moveTo(3.5, 3.75); body.lineTo(7.25, 3.75);
        body.quadTo(7.75, 3.75, 8.1, 4.15); body.lineTo(9.75, 6);
        body.lineTo(16.5, 6); body.quadTo(17.5, 6, 17.5, 7);
        body.lineTo(17.5, 15.5); body.quadTo(17.5, 16.5, 16.5, 16.5);
        body.lineTo(3.5, 16.5); body.quadTo(2.5, 16.5, 2.5, 15.5);
        body.lineTo(2.5, 4.75); body.quadTo(2.5, 3.75, 3.5, 3.75); body.closePath();
        filledOutline(g, body);
    }

    private static void file(Graphics2D g) {
        Path2D.Double body = new Path2D.Double();
        body.moveTo(5, 2.75); body.lineTo(11.5, 2.75); body.lineTo(16, 7.25);
        body.lineTo(16, 16.25); body.quadTo(16, 17.25, 15, 17.25);
        body.lineTo(5, 17.25); body.quadTo(4, 17.25, 4, 16.25);
        body.lineTo(4, 3.75); body.quadTo(4, 2.75, 5, 2.75); body.closePath();
        filledOutline(g, body);
        Path2D.Double fold = new Path2D.Double();
        fold.moveTo(11.5, 2.75); fold.lineTo(11.5, 6.25);
        fold.quadTo(11.5, 7.25, 12.5, 7.25); fold.lineTo(16, 7.25);
        g.draw(fold);
    }

    /** Neutral, lightly filled silhouettes; retain the caller's color and disabled opacity. */
    private static void filledOutline(Graphics2D g, Path2D body) {
        Color outline = g.getColor();
        g.setColor(new Color(outline.getRed(), outline.getGreen(), outline.getBlue(), outline.getAlpha() / 4));
        g.fill(body); g.setColor(outline);
        g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(body);
    }

    private static void path(Graphics2D g, double... points) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(points[0], points[1]);
        for (int index = 2; index < points.length; index += 2) path.lineTo(points[index], points[index + 1]);
        g.draw(path);
    }
}
