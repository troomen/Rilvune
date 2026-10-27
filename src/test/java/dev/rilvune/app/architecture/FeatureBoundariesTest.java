package dev.rilvune.app.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps feature layers independent without adding an architecture-test dependency. */
class FeatureBoundariesTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package ([\\w.]+);");
    private static final Pattern DEPENDENCY = Pattern.compile(
            "\\b(?:dev\\.rilvune|javax\\.swing|java\\.awt|com\\.formdev|org\\.fife)\\.[\\w.]+");

    @Test
    void domainRemainsIndependentOfApplicationAndAdapters() throws IOException {
        assertLayerDependencies("domain", true);
    }

    @Test
    void applicationRemainsIndependentOfAdapters() throws IOException {
        assertLayerDependencies("application", false);
    }

    @Test
    void sourcePackagesMatchFeatureDirectories() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : List.of(MAIN_SOURCES, Path.of("src/test/java"))) {
            for (Path source : sources(root)) {
                String content = Files.readString(source);
                var declaration = PACKAGE.matcher(content);
                if (!declaration.find()) {
                    violations.add(source + ": missing package declaration");
                    continue;
                }
                String packageName = declaration.group(1);
                Path expected = root.resolve(packageName.replace('.', '/')).resolve(source.getFileName());
                if (!source.equals(expected)) violations.add(source + ": expected " + expected);
                if (packageName.matches("dev\\.rilvune\\.(core|application|infrastructure|ui)(\\..*)?")) {
                    violations.add(source + ": layer-first root package");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void sharedTextAndTabsDoNotDependOnFeatureImplementations() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : List.of("text", "tabs", "decoration")) {
            for (Path source : sources(MAIN_SOURCES.resolve("dev/rilvune/platform").resolve(module))) {
                var dependencies = Pattern.compile("\\bdev\\.rilvune\\.[\\w.]+").matcher(Files.readString(source));
                while (dependencies.find()) {
                    if (!dependencies.group().startsWith("dev.rilvune.platform.")) {
                        violations.add(source + ": " + dependencies.group());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
    @Test void sharedEditorDoesNotDependOnConcreteTabImplementations() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources(MAIN_SOURCES.resolve("dev/rilvune/editor"))) {
            var dependencies = Pattern.compile("\\bdev\\.rilvune\\.[\\w.]+").matcher(Files.readString(source));
            while (dependencies.find()) {
                String dependency = dependencies.group();
                if (dependency.startsWith("dev.rilvune.document.") || dependency.startsWith("dev.rilvune.image.")
                        || dependency.startsWith("dev.rilvune.comparison.") || dependency.startsWith("dev.rilvune.git."))
                    violations.add(source + ": " + dependency);
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test void sharedDiffHasNoDocumentFilesystemSessionOrGitDependency() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources(MAIN_SOURCES.resolve("dev/rilvune/diff"))) {
            var dependencies = Pattern.compile("\\bdev\\.rilvune\\.[\\w.]+").matcher(Files.readString(source));
            while (dependencies.find()) {
                String dependency = dependencies.group();
                if (!dependency.startsWith("dev.rilvune.diff.") && !dependency.startsWith("dev.rilvune.platform.")) violations.add(source + ": " + dependency);
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test void gitSnapshotsNeverAcquireLiveDocumentOrFilesystemMutationServices() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources(MAIN_SOURCES.resolve("dev/rilvune/git"))) {
            var dependencies = Pattern.compile("\\bdev\\.rilvune\\.[\\w.]+").matcher(Files.readString(source));
            while (dependencies.find()) {
                String dependency = dependencies.group();
                if (dependency.startsWith("dev.rilvune.document.") || dependency.startsWith("dev.rilvune.comparison.") || dependency.startsWith("dev.rilvune.workspace.") || dependency.startsWith("dev.rilvune.session.")) violations.add(source + ": " + dependency);
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test void projectTreeHasNoGitFeatureDependency() throws IOException {
        String source = Files.readString(MAIN_SOURCES.resolve("dev/rilvune/workspace/ui/ProjectTreeView.java"));
        assertFalse(source.contains("dev.rilvune.git."));
    }

    @Test void agentPresentationAndMainWindowDoNotDependOnHarnessAdapters() throws IOException {
        for (Path source : sources(MAIN_SOURCES.resolve("dev/rilvune/agents/ui"))) {
            String content = Files.readString(source);
            assertFalse(content.contains("dev.rilvune.agents.infrastructure."), source.toString());
            assertFalse(content.contains("com.google.gson."), source.toString());
        }
        String window = Files.readString(MAIN_SOURCES.resolve("dev/rilvune/app/ui/MainWindow.java"));
        assertFalse(window.contains("dev.rilvune.agents."), "The window hosts an injected view/lifecycle, not an agent implementation");
    }

    @Test void documentDoesNotDependOnProjectOrApplicationComposition() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources(MAIN_SOURCES.resolve("dev/rilvune/document"))) {
            var references = DEPENDENCY.matcher(Files.readString(source));
            while (references.find()) {
                String dependency = references.group();
                if (dependency.startsWith("dev.rilvune.app.") || dependency.startsWith("dev.rilvune.workspace.application."))
                    violations.add(source + ": " + dependency);
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static void assertLayerDependencies(String layer, boolean domainOnly) throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Path source : sources(MAIN_SOURCES)) {
            String content = Files.readString(source);
            var declaration = PACKAGE.matcher(content);
            if (!declaration.find() || !hasSegment(declaration.group(1), layer)) continue;
            checked++;
            var dependencies = DEPENDENCY.matcher(content).region(declaration.end(), content.length());
            while (dependencies.find()) {
                String dependency = dependencies.group();
                boolean forbidden = !dependency.startsWith("dev.rilvune.")
                        || hasSegment(dependency, "infrastructure")
                        || hasSegment(dependency, "ui")
                        || hasSegment(dependency, "app")
                        || dependency.startsWith("dev.rilvune.platform.theme.")
                        || (domainOnly && !hasSegment(dependency, "domain"));
                if (forbidden) violations.add(source + ": " + dependency);
            }
        }
        assertTrue(checked > 0, "No sources checked for layer " + layer);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test void syntaxBackendIsIsolatedAndNeverReplacesTheEditorOrGlobalFactory() throws IOException {
        String adapter = "dev/rilvune/platform/text/infrastructure/syntax/rsta/";
        for (Path root : List.of(MAIN_SOURCES, Path.of("src/test/java"))) for (Path source : sources(root)) {
            String content = Files.readString(source);
            boolean backend = root.relativize(source).toString().startsWith(adapter);
            if (Pattern.compile("\\borg\\.fife\\.ui\\.(?:rsyntaxtextarea|rtextarea)\\.").matcher(content).find())
                assertTrue(backend, "RSTA leaked outside its adapter: " + source);
            if (root.equals(MAIN_SOURCES)) {
                assertFalse(Pattern.compile("\\b(?:TokenMakerFactory\\.(?:getDefaultInstance|setDefaultInstance)|ServiceLoader\\.load|putMapping\\s*\\()").matcher(content).find(), source.toString());
                assertFalse(Pattern.compile("\\b(?:RSyntaxTextArea|RSyntaxDocument)\\s*(?:<|\\(|[a-zA-Z_$])").matcher(content).find(), source.toString());
                if (!backend && !source.endsWith("RilvuneApplication.java"))
                    assertFalse(content.contains("dev.rilvune.platform.text.infrastructure.syntax."), source.toString());
            }
        }
    }

    private static boolean hasSegment(String name, String segment) {
        return List.of(name.split("\\.")).contains(segment);
    }

    private static List<Path> sources(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertFalse(sources.isEmpty(), "No Java sources under " + root);
            return sources;
        }
    }
}
