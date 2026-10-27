package dev.editor.platform.decoration;

import java.nio.file.Path;
import java.util.Map;

public record PathDecorations(Path root, Map<Path, PathDecoration> paths, String availability) {
    public PathDecorations { paths = Map.copyOf(paths); }
    public static PathDecorations empty() { return new PathDecorations(null, Map.of(), ""); }
}
