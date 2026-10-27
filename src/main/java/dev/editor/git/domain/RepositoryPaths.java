package dev.editor.git.domain;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Lexical repository identity: never turn a recorded symlink path into its target. */
public final class RepositoryPaths {
    private RepositoryPaths() { }
    public static Path resolve(Path root, String relative) throws IOException {
        try {
            Path path = Path.of(relative);
            if (relative.isEmpty() || relative.indexOf('\0') >= 0 || path.isAbsolute()
                    || !path.normalize().equals(path) || path.startsWith("..") || path.toString().equals("."))
                throw new IOException("Invalid repository-relative path");
            Path resolved = root.resolve(path).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root)) throw new IOException("Path is outside repository");
            return resolved;
        } catch (InvalidPathException invalid) { throw new IOException("Invalid repository path", invalid); }
    }
}
