package dev.editor.platform.text.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

/** Composed annotation, line-number and marker lanes sharing one source geometry and viewport. */
public final class EditorGutter extends JComponent {
    private final JTextArea text;
    private EditorTheme theme;
    private boolean showNumbers = true, interactive;
    private TextLineLayout layout = TextLineLayout.empty();
    private List<TextLineMarker> passive = List.of();
    private GutterPresentation presentation = GutterPresentation.empty();
    private Map<Integer, GutterPresentation.Annotation> annotations = Map.of();
    private GutterActions actions = GutterActions.none();

    EditorGutter(JTextArea text, EditorTheme theme) {
        this.text = text; this.theme = theme; setName("editor.gutter");
        getAccessibleContext().setAccessibleName("Editor gutter");
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                JPopupMenu menu = actions.menu(lineAt(event.getPoint()));
                if (menu != null && menu.getComponentCount() > 0) menu.show(EditorGutter.this, event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            @Override public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                if (event.getX() < annotationWidth()) {
                    GutterPresentation.Annotation annotation = annotations.get(lineAt(event.getPoint()));
                    if (annotation != null && annotation.navigable()) actions.annotation(annotation.line());
                } else markerAt(event.getPoint()).ifPresent(marker -> actions.marker(marker.id()));
            }
            @Override public void mouseMoved(MouseEvent event) {
                GutterPresentation.Annotation annotation = event.getX() < annotationWidth() ? annotations.get(lineAt(event.getPoint())) : null;
                setCursor(Cursor.getPredefinedCursor(markerAt(event.getPoint()).isPresent() || annotation != null && annotation.navigable() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse);
    }
    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() { };
        return accessibleContext;
    }
    void setLayout(TextLineLayout value) { layout = value; repaint(); }
    void applyTheme(EditorTheme value) { theme = value; UiTypography.editorFont(this, value); revalidate(); repaint(); }
    void showNumbers(boolean value) { showNumbers = value; revalidate(); repaint(); }
    void setMarkers(List<TextLineMarker> value) { passive = List.copyOf(value); revalidate(); repaint(); }
    public void setInspection(GutterPresentation value, GutterActions callbacks) {
        presentation = Objects.requireNonNull(value); actions = Objects.requireNonNull(callbacks); interactive = true;
        Map<Integer, GutterPresentation.Annotation> mapped = new HashMap<>();
        for (var annotation : value.annotations()) mapped.put(annotation.line(), annotation);
        annotations = Map.copyOf(mapped);
        getAccessibleContext().setAccessibleDescription(value.message().isEmpty() ? value.markers().size() + " changes; use Next/Previous Change and Show Change actions" : value.message());
        revalidate(); repaint();
    }
    public GutterPresentation presentation() { return presentation; }
    public void dispose() { ToolTipManager.sharedInstance().unregisterComponent(this); actions = GutterActions.none(); annotations = Map.of(); presentation = GutterPresentation.empty(); }
    private int annotationWidth() { return presentation.annotated() ? Math.min(320, 20 + text.getFontMetrics(text.getFont()).charWidth('m') * 24) : 0; }
    private int numberWidth() { return showNumbers ? 22 + Math.max(3, Integer.toString(text.getLineCount()).length()) * text.getFontMetrics(text.getFont()).charWidth('0') : 0; }
    private int markerWidth() { return interactive ? 12 : !showNumbers && !passive.isEmpty() ? 6 : 0; }
    @Override public Dimension getPreferredSize() { return new Dimension(annotationWidth() + numberWidth() + markerWidth(), text.getPreferredSize().height); }
    private int lineAt(Point point) {
        try { return text.getLineOfOffset(text.viewToModel2D(new Point(0, point.y))); }
        catch (BadLocationException error) { return -1; }
    }
    public Rectangle markerBounds(GutterPresentation.Marker marker) {
        try {
            if (marker.endOffset() > text.getDocument().getLength()) return new Rectangle();
            int line = text.getLineOfOffset(marker.startOffset());
            if (layout.fold(line).isPresent()) return new Rectangle();
            Rectangle2D start = text.modelToView2D(marker.startOffset());
            Rectangle2D end = text.modelToView2D(Math.max(marker.startOffset(), marker.endOffset() - 1));
            if (start == null || end == null) return new Rectangle();
            boolean deleted = marker.startOffset() == marker.endOffset();
            int y = (int)start.getY(), height = Math.max(5, (int)(end.getMaxY() - start.getY()));
            if (deleted) { y -= 3; height = 7; }
            return new Rectangle(getWidth() - 12, Math.max(0, y), 12, height);
        } catch (BadLocationException error) { return new Rectangle(); }
    }
    public Optional<GutterPresentation.Marker> markerAt(Point point) {
        if (point.x < getWidth() - markerWidth()) return Optional.empty();
        return visibleMarkers(new Rectangle(point.x, point.y - 4, 1, 9)).stream().filter(marker -> markerBounds(marker).contains(point)).findFirst();
    }
    private List<GutterPresentation.Marker> visibleMarkers(Rectangle clip) {
        List<GutterPresentation.Marker> markers = presentation.markers();
        int first = text.viewToModel2D(new Point(0, Math.max(0, clip.y - text.getFontMetrics(text.getFont()).getHeight())));
        int last = text.viewToModel2D(new Point(0, clip.y + clip.height + text.getFontMetrics(text.getFont()).getHeight()));
        int low = 0, high = markers.size();
        while (low < high) { int mid = (low + high) >>> 1; if (markers.get(mid).endOffset() < first) low = mid + 1; else high = mid; }
        int end = low; while (end < markers.size() && markers.get(end).startOffset() <= last) end++;
        return markers.subList(low, end);
    }
    @Override public String getToolTipText(MouseEvent event) {
        if (event.getX() < annotationWidth()) {
            var annotation = annotations.get(lineAt(event.getPoint()));
            if (annotation != null) return annotation.description().isEmpty() ? null : "Annotation: " + annotation.description();
        }
        return markerAt(event.getPoint()).map(marker -> "Change: " + marker.description()).orElse(presentation.message().isEmpty() ? null : "Editor: " + presentation.message());
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            UiTypography.antialias(g);
            g.setColor(theme.surface()); g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(text.getFont()); FontMetrics metrics = g.getFontMetrics(); Rectangle clip = g.getClipBounds();
            paintPassive(g);
            int first = Math.max(0, lineAt(new Point(0, Math.max(0, clip.y))));
            int last = Math.max(first, lineAt(new Point(0, clip.y + clip.height)));
            int current = text.getLineOfOffset(text.getCaretPosition());
            for (int line = first; line <= last; line++) {
                Rectangle2D bounds = text.modelToView2D(text.getLineStartOffset(line));
                if (bounds == null || bounds.getHeight() == 0 || layout.fold(line).isPresent()) continue;
                int y = (int)bounds.getY();
                if (presentation.annotated()) paintAnnotation(g, annotations.get(line), y, metrics);
                if (showNumbers) {
                    String number = Integer.toString(line + 1); g.setColor(line == current ? theme.text() : theme.muted());
                    g.drawString(number, annotationWidth() + numberWidth() - metrics.stringWidth(number) - 12, y + metrics.getAscent());
                }
            }
            if (presentation.annotated() && annotations.isEmpty()) {
                g.setColor(theme.muted()); g.drawString(ellipsize(presentation.message(), metrics, annotationWidth() - 12), 6, text.getInsets().top + metrics.getAscent());
            }
            for (var marker : visibleMarkers(clip)) {
                Rectangle bounds = markerBounds(marker); if (!bounds.intersects(clip)) continue;
                g.setColor(marker.color());
                if (marker.startOffset() == marker.endOffset()) g.fillPolygon(new int[]{bounds.x + 3, bounds.x + 9, bounds.x + 3}, new int[]{bounds.y, bounds.y + 3, bounds.y + 6}, 3);
                else g.fillRect(bounds.x + 5, bounds.y, 3, bounds.height);
            }
        } catch (BadLocationException ignored) { /* Model/layout may be in transition; source identities never change here. */ }
        finally { g.dispose(); }
    }
    private void paintAnnotation(Graphics2D g, GutterPresentation.Annotation annotation, int y, FontMetrics metrics) {
        if (annotation == null) return;
        if (annotation.navigable()) {
            int alpha = 16 + Math.floorMod(annotation.shade(), 3) * 8;
            g.setColor(new Color(100, 143, 195, alpha)); g.fillRect(0, y, annotationWidth(), metrics.getHeight());
        }
        g.setColor(theme.muted()); g.drawString(ellipsize(annotation.label(), metrics, annotationWidth() - 12), 6, y + metrics.getAscent());
    }
    private String ellipsize(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metrics.stringWidth(text.substring(0, middle) + "…") <= width) low = middle; else high = middle - 1;
        }
        if (low > 0 && low < text.length() && Character.isHighSurrogate(text.charAt(low - 1))) low--;
        return text.substring(0, low) + "…";
    }
    private void paintPassive(Graphics2D g) throws BadLocationException {
        for (TextLineMarker marker : passive) {
            if (marker.endOffset() > text.getDocument().getLength()) continue;
            Rectangle2D a = text.modelToView2D(marker.startOffset()), b = text.modelToView2D(Math.max(marker.startOffset(), marker.endOffset() - 1));
            if (a != null && b != null) { g.setColor(marker.color()); g.fillRect(0, (int)a.getY(), 3, Math.max(3, (int)(b.getMaxY() - a.getY()))); }
        }
    }
}
