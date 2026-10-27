package dev.editor.session.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record SessionState(Path workspace, List<TabDescriptor> tabs, String activeId,
                           int x, int y, int width, int height,
                           double projectFraction, boolean projectVisible, boolean agentsVisible,
                           List<String> tabOrder) {
    public SessionState {
        tabOrder = List.copyOf(tabOrder);
        var order = tabOrder;
        tabs = tabs.stream().sorted(java.util.Comparator.comparingInt(tab -> { int index = order.indexOf(tab.id()); return index < 0 ? Integer.MAX_VALUE : index; })).toList();
    }
    public List<TextTabDescriptor> files() { return tabs.stream().filter(TextTabDescriptor.class::isInstance).map(TextTabDescriptor.class::cast).toList(); }
    public List<ComparisonTabDescriptor> comparisons() { return tabs.stream().filter(ComparisonTabDescriptor.class::isInstance).map(ComparisonTabDescriptor.class::cast).toList(); }
    public List<ImageTabDescriptor> images() { return tabs.stream().filter(ImageTabDescriptor.class::isInstance).map(ImageTabDescriptor.class::cast).toList(); }
    public SessionState(Path workspace, List<TextTabDescriptor> files, String activeId, int x, int y, int width, int height,
                        double projectFraction, boolean projectVisible, boolean agentsVisible) {
        this(workspace, List.copyOf(files), activeId, x, y, width, height, projectFraction, projectVisible, agentsVisible, List.of());
    }
    public record TextTabDescriptor(String id, Path path, int caret) implements TabDescriptor { }
    public record ImageTabDescriptor(String id, Path path) implements TabDescriptor {
        public ImageTabDescriptor { if (id == null || id.isBlank() || id.length() > 256) throw new IllegalArgumentException("Invalid image identity"); Objects.requireNonNull(path); }
    }
    public record Source(String id, Path path) {
        public Source {
            Objects.requireNonNull(id, "Source identity");
            if (id.isBlank() && path == null) throw new IllegalArgumentException("A source needs a document identity or path");
        }
    }
    public record ComparisonTabDescriptor(String id, Source left, Source right, int leftCaret, int rightCaret,
                             int leftScroll, int rightScroll, boolean unified, boolean ignoreWhitespace) implements TabDescriptor {
        public ComparisonTabDescriptor {
            if (Objects.requireNonNull(id, "Comparison identity").isBlank()) throw new IllegalArgumentException("Comparison identity is empty");
            Objects.requireNonNull(left, "Left source"); Objects.requireNonNull(right, "Right source");
            if (leftCaret < 0 || rightCaret < 0 || leftScroll < 0 || rightScroll < 0) throw new IllegalArgumentException("Negative comparison position");
        }
    }
    public static SessionState empty(Path workspace) {
        return new SessionState(workspace, List.of(), "", -1, -1, 1440, 900, 0.24, true, false);
    }
}
