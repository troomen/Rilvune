package dev.rilvune.diff.ui;

import dev.rilvune.diff.domain.*;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextLineMarker;

import javax.swing.text.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/** Owns removable decorations using the common diff palette and original source ranges. */
public final class DiffHighlights implements AutoCloseable {
    private final TextEditor editor;
    private final boolean gutterMarkers;
    private final List<Object> tags = new ArrayList<>();
    DiffHighlights(TextEditor editor) { this(editor, true); }
    /** Independent highlight ownership; source editors retain their existing interactive gutter. */
    public DiffHighlights(TextEditor editor, boolean gutterMarkers) {
        this.editor = editor; this.gutterMarkers = gutterMarkers;
    }
    /** Paint one side of a selected hunk, optionally rebased into a cropped source viewport. */
    public void showHunk(DiffResult diff, int index, Side side, int sourceOffset) {
        close();
        paint(diff.hunks().subList(index, index + 1), side, sourceOffset);
    }

    void show(DiffResult diff, Side side) {
        close();
        paint(diff.hunks(), side, 0);
    }
    private void paint(List<DiffHunk> hunks, Side side, int sourceOffset) {
        List<TextLineMarker> markers = new ArrayList<>();
        for (DiffHunk hunk : hunks) for (DiffLineChange change : hunk.changes()) {
            if (change.kind() != DiffKind.CHANGED) {
                TextSpan span = shift(change.range(side).span(), sourceOffset);
                markers.add(new TextLineMarker(span.startOffset(), span.endOffset(), DiffColors.edge(change.kind())));
                if (!add(span, change.kind(), true)) return;
            } else for (DiffFragment fragment : change.fragments()) {
                TextSpan span = shift(fragment.span(side), sourceOffset);
                if (span.empty()) continue;
                markers.add(new TextLineMarker(span.startOffset(), span.endOffset(), DiffColors.edge(fragment.kind())));
                if (!add(span, fragment.kind(), false)) return;
            }
        }
        if (gutterMarkers) editor.setLineMarkers(markers);
    }
    private static TextSpan shift(TextSpan span, int offset) {
        return new TextSpan(span.startOffset() - offset, span.endOffset() - offset);
    }
    void showUnified(UnifiedText.Projection projection) {
        close();
        List<TextLineMarker> markers = new ArrayList<>();
        for (UnifiedText.Change change : projection.changes()) {
            markers.add(new TextLineMarker(change.start(), change.end(), DiffColors.edge(change.kind())));
            if (!add(new TextSpan(change.start(), change.end()), change.kind(), change.wholeLine())) return;
        }
        if (gutterMarkers) editor.setLineMarkers(markers);
    }
    private boolean add(TextSpan span, DiffKind kind, boolean wholeLine) {
        if (span.empty()) return true;
        Highlighter.HighlightPainter painter = wholeLine ? new Lines(DiffColors.fill(kind))
                : new DefaultHighlighter.DefaultHighlightPainter(DiffColors.fill(kind));
        try { tags.add(editor.textComponent().getHighlighter().addHighlight(span.startOffset(), span.endOffset(), painter)); return true; }
        catch (BadLocationException failure) { close(); return false; }
    }
    @Override public void close() {
        for (Object tag : tags) editor.textComponent().getHighlighter().removeHighlight(tag);
        tags.clear(); if (gutterMarkers) editor.setLineMarkers(List.of());
    }
    private static final class Lines extends LayeredHighlighter.LayerPainter {
        private final Color color;
        private Lines(Color color) { this.color = color; }
        @Override public void paint(Graphics graphics, int first, int end, Shape bounds, JTextComponent text) { fill(graphics, first, end, text); }
        @Override public Shape paintLayer(Graphics graphics, int first, int end, Shape bounds, JTextComponent text, View view) {
            return fill(graphics, first, end, text);
        }
        private Shape fill(Graphics graphics, int first, int end, JTextComponent text) {
            try {
                Rectangle2D a = text.modelToView2D(first);
                Rectangle2D b = text.modelToView2D(Math.max(first, end - 1));
                if (a == null || b == null) return null;
                Rectangle area = new Rectangle(0, (int) a.getY(), text.getWidth(), Math.max(1, (int) (b.getMaxY() - a.getY())));
                graphics.setColor(color); graphics.fillRect(area.x, area.y, area.width, area.height); return area;
            } catch (BadLocationException ignored) { return null; }
        }
    }
}
