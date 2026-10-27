package dev.rilvune.image.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.editor.ui.EditorTabView;
import dev.rilvune.image.domain.ImageTabState;
import dev.rilvune.platform.decoration.*;
import dev.rilvune.platform.tabs.ui.TabPresentation;
import dev.rilvune.platform.theme.*;
import dev.rilvune.session.domain.*;
import dev.rilvune.settings.domain.EditorSettings;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.*;

/** Reusable viewport; pixels remain immutable while displayed. No I/O in painting or controls. */
public final class ImageTabView extends JPanel implements EditorTabView {
    private final ImageTabController controller;
    private final JScrollPane scroll;
    private final ImageCanvas canvas = new ImageCanvas();
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final JLabel zoomLabel = label("100%");
    private final JLabel metadata = label("");
    private final JLabel message = label("");
    private EditorTheme theme;
    private PathDecorations decorations = PathDecorations.empty();
    private BufferedImage pixels;
    private boolean fit = true;
    private double zoom = 1;
    private boolean updating;
    private Point reloadPosition;
    private String displayName;
    private final Action reload;
    public ImageTabView(ImageTabController controller, EditorTheme theme) {
        super(new BorderLayout()); this.controller = controller; this.theme = theme;
        setName("image.preview"); setFocusable(true);
        reload = new AbstractAction("Reload") { public void actionPerformed(ActionEvent event) { controller.reload(); } };
        toolbar.add(button("Fit", () -> { fit = true; updateGeometry(); }));
        toolbar.add(button("100%", () -> setZoom(1, null)));
        toolbar.add(button("−", () -> setZoom(zoom / 1.25, null)));
        toolbar.add(button("+", () -> setZoom(zoom * 1.25, null)));
        toolbar.add(zoomLabel); toolbar.add(new JButton(reload)); toolbar.add(metadata);
        scroll = new JScrollPane(canvas); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24); scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent event) { updateGeometry(); } });
        scroll.addMouseWheelListener(event -> {
            if (!event.isControlDown() || pixels == null) return;
            event.consume(); setZoom(zoom * Math.pow(1.2, -event.getPreciseWheelRotation()), SwingUtilities.convertPoint(scroll, event.getPoint(), canvas));
        });
        // Disable default wheel scrolling during Ctrl+wheel without affecting normal scrolling.
        scroll.setWheelScrollingEnabled(false);
        scroll.addMouseWheelListener(event -> {
            if (event.isControlDown()) return;
            var bar = event.isShiftDown() ? scroll.getHorizontalScrollBar() : scroll.getVerticalScrollBar();
            bar.setValue(bar.getValue() + (int)Math.round(event.getPreciseWheelRotation() * bar.getUnitIncrement() * event.getScrollAmount()));
        });
        var drag = new MouseAdapter() {
            private Point anchor;
            private Point startPosition;
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    anchor = SwingUtilities.convertPoint(canvas, event.getPoint(), scroll.getViewport());
                    startPosition = scroll.getViewport().getViewPosition();
                }
            }
            public void mouseDragged(MouseEvent event) {
                if (anchor == null) return;
                Point pointer = SwingUtilities.convertPoint(canvas, event.getPoint(), scroll.getViewport());
                moveViewport(new Point(startPosition.x + anchor.x - pointer.x, startPosition.y + anchor.y - pointer.y));
            }
            public void mouseReleased(MouseEvent event) { anchor = null; }
        };
        canvas.addMouseListener(drag); canvas.addMouseMotionListener(drag);
        message.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12)); message.setName("image.message");
        add(toolbar, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER); add(message, BorderLayout.SOUTH);
        applySettings(EditorSettings.defaults(), theme);
    }
    void pixels(BufferedImage value) {
        if (value == null && pixels != null && !fit) reloadPosition = scroll.getViewport().getViewPosition();
        pixels = value; updateGeometry();
        if (value != null && reloadPosition != null) { if (!fit) moveViewport(reloadPosition); reloadPosition = null; }
        canvas.repaint();
    }
    @Override public ImageTabController controller() { return controller; }
    @Override public String id() { return controller.model().id(); }
    @Override public JComponent component() { return this; }
    @Override public TabDescriptor descriptor() { return new SessionState.ImageTabDescriptor(id(), controller.model().state().path()); }
    @Override public Set<Path> filePaths() { return Set.of(controller.model().state().path()); }
    @Override public Optional<Path> activeFilePath() { return Optional.of(controller.model().state().path()); }
    @Override public List<Action> contextActions() { return List.of(reload); }
    @Override public void selected(boolean active) { if (active) controller.checkForChanges(); }
    @Override public void setDecorations(PathDecorations value) { decorations = value; }
    @Override public TabPresentation presentation() {
        var state = controller.model().state(); Path path = state.path();
        var decoration = decorations.root() != null && path.startsWith(decorations.root()) ? decorations.paths().get(path) : null;
        Color normal = decoration == null ? theme.text() : FileStatusColors.color(decoration.tone(), false);
        Color selected = decoration == null ? normal : FileStatusColors.color(decoration.tone(), true);
        return new TabPresentation((displayName == null ? path.getFileName().toString() : displayName) + (state.stale() ? " · stale" : ""), path + " · Read-only image preview", normal, selected);
    }
    void displayName(String name) { displayName = name; }
    @Override public void refreshState() {
        var state = controller.model().state();
        message.setText(state.stale() ? "File changed on disk. Showing an older preview — Reload to update." : state.message());
        metadata.setText(state.phase() == ImageTabState.Phase.READY ? state.format() + " · " + state.width() + " × " + state.height() + " · " + state.encodedBytes() + " bytes" : "");
        metadata.setToolTipText(state.phase() == ImageTabState.Phase.READY ? state.message() : null);
        reload.setEnabled(!controller.disposed());
        canvas.repaint();
    }
    @Override public void applySettings(EditorSettings settings, EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface()); toolbar.setBackground(theme.raised());
        scroll.getViewport().setBackground(theme.surface()); canvas.setBackground(theme.surface());
        for (var label : List.of(metadata, message, zoomLabel)) { label.setForeground(theme.muted()); UiTypography.interfaceFont(label, theme); }
        UiTypography.apply(this, theme);
        updateGeometry(); repaint();
    }
    @Override public void preserveViewState(Runnable update) {
        boolean previousFit = fit; double previousZoom = zoom; Point position = scroll.getViewport().getViewPosition();
        try { update.run(); } finally { fit = previousFit; zoom = previousZoom; updateGeometry(); if (!fit) moveViewport(position); }
    }
    @Override public void focusContent() { requestFocusInWindow(); }
    @Override public CompletableFuture<Boolean> requestClose() { return controller.requestClose(); }
    @Override public void dispose() { controller.dispose(); pixels = null; }
    public double zoom() { return zoom; }
    public boolean fit() { return fit; }
    private void setZoom(double value, Point pointer) {
        if (pixels == null) return;
        var viewport = scroll.getViewport();
        Point oldPosition = viewport.getViewPosition(); Dimension extent = viewport.getExtentSize();
        Point anchor = pointer == null ? new Point(oldPosition.x + extent.width / 2, oldPosition.y + extent.height / 2) : pointer;
        double imageX = (anchor.x - offsetX()) / zoom, imageY = (anchor.y - offsetY()) / zoom;
        Point relative = new Point(anchor.x - oldPosition.x, anchor.y - oldPosition.y);
        fit = false; zoom = Math.clamp(value, 0.125, 8); updateGeometry();
        moveViewport(new Point((int)Math.round(offsetX() + imageX * zoom) - relative.x, (int)Math.round(offsetY() + imageY * zoom) - relative.y));
    }
    private void updateGeometry() {
        if (scroll == null || updating) return; updating = true;
        try {
            Dimension extent = scroll.getViewport().getExtentSize();
            if (fit && pixels != null && extent.width > 0 && extent.height > 0) zoom = Math.min(1, Math.min((double)extent.width / pixels.getWidth(), (double)extent.height / pixels.getHeight()));
            int width = pixels == null ? 1 : Math.max(1, (int)Math.ceil(pixels.getWidth() * zoom));
            int height = pixels == null ? 1 : Math.max(1, (int)Math.ceil(pixels.getHeight() * zoom));
            canvas.setPreferredSize(new Dimension(Math.max(extent.width, width), Math.max(extent.height, height)));
            scroll.getViewport().setViewSize(canvas.getPreferredSize()); canvas.revalidate();
            zoomLabel.setText(String.format(Locale.ROOT, "%.1f%%", zoom * 100)); canvas.repaint();
        } finally { updating = false; }
    }
    private int offsetX() { return pixels == null ? 0 : Math.max(0, (canvas.getWidth() - (int)Math.round(pixels.getWidth() * zoom)) / 2); }
    private int offsetY() { return pixels == null ? 0 : Math.max(0, (canvas.getHeight() - (int)Math.round(pixels.getHeight() * zoom)) / 2); }
    private void moveViewport(Point point) {
        var viewport = scroll.getViewport();
        viewport.setViewPosition(new Point(Math.clamp(point.x, 0, Math.max(0, canvas.getWidth() - viewport.getWidth())), Math.clamp(point.y, 0, Math.max(0, canvas.getHeight() - viewport.getHeight()))));
    }
    private final class ImageCanvas extends JComponent {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            try {
                UiTypography.antialias(g);
                g.setColor(theme.surface()); g.fillRect(0, 0, getWidth(), getHeight());
                if (pixels == null) {
                    g.setColor(theme.muted()); g.setFont(theme.interfaceFont());
                    g.drawString(controller.model().state().phase() == ImageTabState.Phase.LOADING ? "Loading image…" : "Image preview unavailable", 24, 36); return;
                }
                int x = offsetX(), y = offsetY(), width = (int)Math.round(pixels.getWidth() * zoom), height = (int)Math.round(pixels.getHeight() * zoom);
                g.clipRect(x, y, width, height);
                Rectangle clip = g.getClipBounds();
                for (int row = Math.max(y, clip.y) / 12 * 12; row < Math.min(y + height, clip.y + clip.height); row += 12)
                    for (int column = Math.max(x, clip.x) / 12 * 12; column < Math.min(x + width, clip.x + clip.width); column += 12) {
                        g.setColor(((row / 12 + column / 12) & 1) == 0 ? theme.raised() : theme.border()); g.fillRect(column, row, 12, 12);
                    }
                Object interpolation = zoom >= 1 && Math.abs(zoom - Math.rint(zoom)) < 0.00001 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation); g.drawImage(pixels, x, y, width, height, null);
            } finally { g.dispose(); }
        }
    }
    private static JLabel label(String text) { var label = new JLabel(text); label.putClientProperty("html.disable", true); return label; }
    private static JButton button(String text, Runnable run) { var button = new JButton(text); button.putClientProperty("JButton.buttonType", "toolBarButton"); button.addActionListener(event -> run.run()); return button; }
}
