package dev.editor.platform.text.ui.minimap;

import dev.editor.platform.text.ui.TextLineLayout;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.UiTypography;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.util.function.Supplier;

/**
 * Read-only overview of an existing text viewport. All Swing geometry access stays on the EDT.
 * Preview work is sampled, debounced and split into bounded batches; no source-sized copy is kept.
 */
public final class TextMinimap extends JPanel implements AutoCloseable {
    private static final float PREVIEW_FONT_SIZE = 1.5f;
    private static final int MAXIMUM_SOURCE_CHARACTERS = 256;
    private static final int MAXIMUM_PREVIEW_COLUMNS = 160;
    private final JTextArea text;
    private final JViewport viewport;
    private final Supplier<TextLineLayout> layout;
    private final Timer debounce;
    private final DocumentListener edits;
    private final PropertyChangeListener properties;
    private final ChangeListener scrolling;
    private final ComponentAdapter sizing;
    private final HierarchyListener visibility;
    private Document document;
    private EditorTheme theme;
    private BufferedImage preview;
    private long generation;
    private boolean dirty = true, closed, dragging;
    private double grabOffset;

    public TextMinimap(JTextArea text, JViewport viewport, Supplier<TextLineLayout> layout, EditorTheme theme) {
        this.text = text; this.viewport = viewport; this.layout = layout; this.theme = theme;
        setName("text.minimap"); setPreferredSize(new Dimension(MinimapGeometry.WIDTH, 0)); setMinimumSize(new Dimension(0, 0));
        setFocusable(true); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Document minimap · click or drag to scroll");
        getAccessibleContext().setAccessibleName("Document minimap");
        getAccessibleContext().setAccessibleDescription("Text overview. Click or drag to scroll; Page Up, Page Down, Home and End navigate without editing.");
        debounce = new Timer(120, event -> beginPreview()); debounce.setRepeats(false);
        edits = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { invalidatePreview(); }
            public void removeUpdate(DocumentEvent event) { invalidatePreview(); }
            public void changedUpdate(DocumentEvent event) { invalidatePreview(); }
        };
        document = text.getDocument(); document.addDocumentListener(edits);
        properties = event -> {
            if ("document".equals(event.getPropertyName())) {
                document.removeDocumentListener(edits); document = text.getDocument(); document.addDocumentListener(edits);
            }
            switch (event.getPropertyName()) {
                case "document", "font", "tabSize", "lineWrap", "wrapStyleWord", "UI", "border" -> invalidatePreview();
                default -> { }
            }
        };
        text.addPropertyChangeListener(properties);
        scrolling = event -> repaint(); viewport.addChangeListener(scrolling);
        sizing = new ComponentAdapter() { public void componentResized(ComponentEvent event) { invalidatePreview(); } };
        text.addComponentListener(sizing); addComponentListener(sizing);
        visibility = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) invalidatePreview(); else pausePreview();
            }
        };
        addHierarchyListener(visibility);
        MouseAdapter navigation = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (closed || !SwingUtilities.isLeftMouseButton(event)) return;
                MinimapGeometry geometry = geometry();
                Rectangle shown = geometry.viewport(viewport.getViewPosition().y, viewport.getExtentSize().height);
                grabOffset = shown.contains(event.getPoint()) ? geometry.documentY(event.getY()) - viewport.getViewPosition().y
                        : viewport.getExtentSize().height / 2.0;
                dragging = true; navigate(event.getY());
            }
            @Override public void mouseDragged(MouseEvent event) { if (dragging) navigate(event.getY()); }
            @Override public void mouseReleased(MouseEvent event) { dragging = false; }
        };
        addMouseListener(navigation); addMouseMotionListener(navigation);
        bind("PAGE_UP", () -> scrollTo(viewport.getViewPosition().y - viewport.getExtentSize().height));
        bind("PAGE_DOWN", () -> scrollTo(viewport.getViewPosition().y + viewport.getExtentSize().height));
        bind("HOME", () -> scrollTo(0)); bind("END", () -> scrollTo(text.getHeight()));
        applyTheme(theme);
    }

    public void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface()); UiTypography.apply(this, theme); invalidatePreview();
    }

    public void invalidatePreview() {
        if (closed) return;
        generation++; dirty = true;
        if (isShowing()) debounce.restart();
        repaint();
    }

    private void pausePreview() {
        generation++; debounce.stop(); preview = null; dirty = true; dragging = false;
    }

    private double glyphScale() { return Math.min(.25, PREVIEW_FONT_SIZE / text.getFont().getSize2D()); }
    private MinimapGeometry geometry() { return MinimapGeometry.of(getWidth(), getHeight(), text.getHeight(), glyphScale()); }

    private void beginPreview() {
        if (closed || !isShowing() || getWidth() <= 0 || getHeight() <= 0 || text.getWidth() <= 0) return;
        dirty = false;
        MinimapGeometry geometry = geometry();
        PreviewWork work = new PreviewWork(generation, geometry, glyphScale(),
                new BufferedImage(geometry.width(), geometry.height(), BufferedImage.TYPE_INT_ARGB));
        Graphics2D g = work.image.createGraphics();
        try { g.setColor(theme.surface()); g.fillRect(0, 0, geometry.width(), geometry.height()); }
        finally { g.dispose(); }
        renderBatch(work);
    }

    private void renderBatch(PreviewWork work) {
        if (closed || !isShowing() || work.generation != generation) return;
        Graphics2D g = work.image.createGraphics();
        try {
            UiTypography.antialias(g); g.setColor(theme.muted());
            Font miniature = text.getFont().deriveFont((float)(text.getFont().getSize2D() * work.glyphScale));
            g.setFont(miniature);
            int ascent = g.getFontMetrics().getAscent();
            int step = Math.max(2, (int)Math.ceil(text.getFontMetrics(text.getFont()).getHeight() * work.glyphScale));
            long deadline = System.nanoTime() + 4_000_000;
            for (int rows = 0; work.y < work.geometry.height() && rows < 64; rows++, work.y += step) {
                double sourceY = work.geometry.documentY(work.y + step / 2.0);
                int offset = text.viewToModel2D(new Point(text.getInsets().left, (int)sourceY));
                if (offset < 0) continue;
                Rectangle2D row = text.modelToView2D(offset);
                if (row == null || row.getHeight() <= 0 || sourceY < row.getY() || sourceY >= row.getMaxY()
                        || row.getY() == work.previousRow) continue;
                work.previousRow = row.getY();
                String sample = sample(offset);
                g.drawString(sample, 3, (int)Math.round(row.getY() * work.geometry.scale()) + ascent);
                if (System.nanoTime() >= deadline) { work.y += step; break; }
            }
        } catch (BadLocationException invalidated) {
            invalidatePreview(); return;
        } finally { g.dispose(); }
        if (work.y < work.geometry.height()) SwingUtilities.invokeLater(() -> renderBatch(work));
        else if (work.generation == generation) { preview = work.image; repaint(); }
    }

    private String sample(int offset) throws BadLocationException {
        int line = text.getLineOfOffset(offset);
        if (layout.get().fold(line).isPresent()) return "⋯";
        int end = Math.min(text.getLineEndOffset(line), offset + MAXIMUM_SOURCE_CHARACTERS);
        if (text.getLineWrap()) {
            Rectangle2D row = text.modelToView2D(offset);
            int last = text.viewToModel2D(new Point(Math.max(0, text.getWidth() - text.getInsets().right - 1), (int)row.getY()));
            end = Math.min(end, Math.max(offset, last));
        }
        String source = document.getText(offset, Math.max(0, Math.min(end, document.getLength()) - offset));
        StringBuilder expanded = new StringBuilder(); int column = 0;
        for (int index = 0; index < source.length() && column < MAXIMUM_PREVIEW_COLUMNS;) {
            char first = source.charAt(index);
            if (Character.isHighSurrogate(first) && index + 1 == source.length()) break;
            int value = source.codePointAt(index); index += Character.charCount(value);
            if (value == '\n' || value == '\r') break;
            if (value == '\t') {
                int spaces = Math.min(MAXIMUM_PREVIEW_COLUMNS - column, text.getTabSize() - column % text.getTabSize());
                expanded.append(" ".repeat(spaces)); column += spaces;
            } else { expanded.appendCodePoint(value); column++; }
        }
        return expanded.toString();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (closed) return;
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            if (preview != null) g.drawImage(preview, 0, 0, null);
            MinimapGeometry geometry = geometry();
            Rectangle shown = geometry.viewport(viewport.getViewPosition().y, viewport.getExtentSize().height);
            Color accent = theme.accent(); g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 32));
            g.fillRect(shown.x, shown.y, shown.width, shown.height);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
            g.drawRect(shown.x, shown.y, Math.max(0, shown.width - 1), Math.max(0, shown.height - 1));
            g.setColor(theme.border()); g.drawLine(0, 0, 0, getHeight());
        } finally { g.dispose(); }
        if (dirty && isShowing() && !debounce.isRunning()) debounce.restart();
    }

    private void navigate(int y) { scrollTo(geometry().viewY(y, grabOffset, viewport.getExtentSize().height)); }
    private void scrollTo(int y) {
        if (closed) return;
        Point current = viewport.getViewPosition();
        current.y = Math.clamp(y, 0, Math.max(0, text.getHeight() - viewport.getExtentSize().height));
        viewport.setViewPosition(current);
    }
    private void bind(String key, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        getActionMap().put(key, new AbstractAction() { public void actionPerformed(ActionEvent event) { action.run(); } });
    }

    @Override public void close() {
        if (closed) return;
        closed = true; pausePreview();
        document.removeDocumentListener(edits); text.removePropertyChangeListener(properties);
        viewport.removeChangeListener(scrolling); text.removeComponentListener(sizing); removeComponentListener(sizing);
        removeHierarchyListener(visibility);
    }

    private static final class PreviewWork {
        final long generation;
        final MinimapGeometry geometry;
        final double glyphScale;
        final BufferedImage image;
        int y;
        double previousRow = -1;
        PreviewWork(long generation, MinimapGeometry geometry, double glyphScale, BufferedImage image) {
            this.generation = generation; this.geometry = geometry; this.glyphScale = glyphScale; this.image = image;
        }
    }
}
