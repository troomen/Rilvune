package dev.rilvune.app;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit launch paths remain identical on host and in the Docker container. */
public record LaunchOptions(Path workspace, Path browseRoot, Path profile, Optional<Path> snapshot,
                            Optional<Path> openFile, Optional<Path> compareFile, String snapshotView) {
    public static LaunchOptions parse(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        Set<String> supported = Set.of("--workspace", "--browse-root", "--profile", "--snapshot", "--open", "--compare", "--snapshot-view");
        for (int index = 0; index < arguments.length; index += 2) {
            String key = arguments[index];
            if (!supported.contains(key)) throw new IllegalArgumentException("Unknown argument: " + key);
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--") || arguments[index + 1].isBlank()) throw new IllegalArgumentException("Missing value for " + key);
            if (values.putIfAbsent(key, arguments[index + 1]) != null) throw new IllegalArgumentException("Duplicate argument: " + key);
        }
        Path workspace = values.containsKey("--workspace") ? absolute(values.get("--workspace")) : null;
        Path defaultRoot = workspace == null ? absolute(".") : workspace.getParent() == null ? workspace : workspace.getParent();
        Path browseRoot = values.containsKey("--browse-root") ? absolute(values.get("--browse-root")) : defaultRoot;
        if (workspace != null && !workspace.startsWith(browseRoot)) throw new IllegalArgumentException("--workspace must be inside --browse-root");
        if (values.containsKey("--compare") && !values.containsKey("--open")) throw new IllegalArgumentException("--compare requires --open");
        String view = values.getOrDefault("--snapshot-view", "editor");
        if (!Set.of("editor", "appearance", "font").contains(view)) throw new IllegalArgumentException("Unknown snapshot view: " + view);
        return new LaunchOptions(workspace, browseRoot, absolute(values.getOrDefault("--profile", ".local")),
                Optional.ofNullable(values.get("--snapshot")).map(LaunchOptions::absolute),
                Optional.ofNullable(values.get("--open")).map(Path::of), Optional.ofNullable(values.get("--compare")).map(Path::of), view);
    }
    private static Path absolute(String value) { return Path.of(value).toAbsolutePath().normalize(); }
}
