package dev.editor.platform.text.ui;

import java.awt.Color;
import java.util.List;
import java.util.Objects;

/** View-only immutable source coordinates. IDs are interpreted by the supplied host callbacks. */
public record GutterPresentation(List<Marker> markers, List<Annotation> annotations, boolean annotated, String message) {
    public record Marker(int id, int startOffset, int endOffset, Color color, String description) {
        public Marker { Objects.requireNonNull(color); Objects.requireNonNull(description); if (id < 0 || startOffset < 0 || endOffset < startOffset) throw new IllegalArgumentException("Invalid marker range"); }
    }
    public record Annotation(int line, String label, String description, boolean navigable, int shade) {
        public Annotation { if (line < 0) throw new IllegalArgumentException("Invalid annotation line"); Objects.requireNonNull(label); Objects.requireNonNull(description); }
    }
    public GutterPresentation {
        markers = List.copyOf(markers); annotations = List.copyOf(annotations); Objects.requireNonNull(message);
        int end = 0;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Marker marker : markers) {
            if (marker.startOffset() < end || !ids.add(marker.id())) throw new IllegalArgumentException("Markers must be ordered and have unique IDs");
            end = marker.endOffset();
        }
    }
    public static GutterPresentation empty() { return new GutterPresentation(List.of(), List.of(), false, ""); }
}
