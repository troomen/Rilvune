package dev.rilvune.diff.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.diff.domain.*;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.theme.EditorTheme;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Paints source geometry without inserting alignment characters or lines into either document. */
final class DiffGutter extends JComponent {
    private final TextEditor left;
    private final TextEditor right;
    private final Predicate<Side> allowed;
    private final List<Hit> hits = new ArrayList<>();
    private EditorTheme theme;
    private DiffResult result;

    DiffGutter(TextEditor left, TextEditor right, EditorTheme theme, Predicate<Side> allowed, BiConsumer<DiffHunk, Side> transfer) {
        this.left = left; this.right = right; this.theme = theme; this.allowed = allowed;
        setName("diff.gutter");
        setPreferredSize(new Dimension(64, 0)); setMinimumSize(new Dimension(64, 0));
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                hits.stream().filter(hit -> hit.bounds.contains(event.getPoint()) && allowed.test(hit.from.opposite()))
                        .findFirst().ifPresent(hit -> transfer.accept(hit.hunk, hit.from));
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                setCursor(Cursor.getPredefinedCursor(hits.stream().anyMatch(hit -> hit.bounds.contains(event.getPoint()) && allowed.test(hit.from.opposite()))
                        ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }
    void show(DiffResult value) { result = value; hits.clear(); repaint(); }
    void applyTheme(EditorTheme value) { theme = value; repaint(); }
    @Override public String getToolTipText(MouseEvent event) {
        return hits.stream().filter(hit -> hit.bounds.contains(event.getPoint())).findFirst()
                .map(hit -> hit.from == Side.LEFT ? "Use left change in right file" : "Use right change in left file").orElse(null);
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(theme.surface()); g.fillRect(0, 0, getWidth(), getHeight());
            hits.clear(); if (result == null) return;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            UiTypography.antialias(g);
            Rectangle clip = viewportClip(left).intersection(viewportClip(right));
            g.clip(clip);
            for (DiffHunk hunk : result.hunks()) {
                double[] a = bounds(left, hunk.left()), b = bounds(right, hunk.right());
                if (a == null || b == null) continue;
                if (Math.max(a[1], b[1]) < clip.y || Math.min(a[0], b[0]) > clip.getMaxY()) continue;
                for (DiffLineChange change : hunk.changes()) band(g, change);
                button(g, hunk, Side.LEFT, getWidth() - 27, (int) ((a[0] + a[1]) / 2));
                button(g, hunk, Side.RIGHT, 3, (int) ((b[0] + b[1]) / 2));
            }
        } finally { g.dispose(); }
    }
    private void band(Graphics2D g, DiffLineChange change) {
        double[] a = bounds(left, change.left()), b = bounds(right, change.right());
        if (a == null || b == null) return;
        Path2D band = new Path2D.Double();
        double middle = getWidth() / 2.0;
        band.moveTo(0, a[0]); band.curveTo(middle, a[0], middle, b[0], getWidth(), b[0]);
        band.lineTo(getWidth(), b[1]); band.curveTo(middle, b[1], middle, a[1], 0, a[1]); band.closePath();
        g.setColor(DiffColors.fill(change.kind())); g.fill(band);
        g.setColor(DiffColors.edge(change.kind())); g.draw(band);
    }
    private Rectangle viewportClip(TextEditor editor) {
        JViewport viewport = editor.scrollPane().getViewport();
        Point origin = SwingUtilities.convertPoint(viewport, 0, 0, this);
        return new Rectangle(0, origin.y, getWidth(), viewport.getHeight());
    }
    private double[] bounds(TextEditor editor, TextRange range) {
        try {
            Rectangle2D start = editor.textComponent().modelToView2D(range.startOffset());
            Rectangle2D end = editor.textComponent().modelToView2D(Math.max(range.startOffset(), range.endOffset() - 1));
            if (start == null || end == null) return null;
            Point origin = SwingUtilities.convertPoint(editor.textComponent(), 0, 0, this);
            double top = origin.y + start.getY();
            return new double[]{top, range.empty() ? top + 2 : origin.y + end.getMaxY()};
        } catch (BadLocationException ignored) { return null; }
    }
    private void button(Graphics2D g, DiffHunk hunk, Side from, int x, int center) {
        if (!allowed.test(from.opposite())) return;
        Rectangle bounds = new Rectangle(x, center - 12, 24, 24);
        if (!hits.stream().anyMatch(hit -> hit.bounds.intersects(bounds))) {
            g.setColor(theme.raised()); g.fillRoundRect(x, bounds.y, 24, 24, 5, 5);
            g.setColor(theme.text()); g.setFont(theme.interfaceFont().deriveFont(16f));
            String arrow = from == Side.LEFT ? "→" : "←";
            g.drawString(arrow, x + (24 - g.getFontMetrics().stringWidth(arrow)) / 2, center + 5);
            hits.add(new Hit(bounds, hunk, from));
        }
    }
    void dispose() { ToolTipManager.sharedInstance().unregisterComponent(this); hits.clear(); }
    private record Hit(Rectangle bounds, DiffHunk hunk, Side from) { }
}
