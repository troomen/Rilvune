package dev.editor.workspace.infrastructure;

import dev.editor.workspace.domain.PartialMoveException;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;

/**
 * Blocking filesystem operations. The application must flush/pause affected
 * document queues before calling these methods and rebind them after success.
 * Links are copied/moved as links; ancestors containing links are rejected.
 */
public final class WorkspaceOperations {
    private final Path browseRoot;
    private final Path profileDirectory;

    public WorkspaceOperations(Path browseRoot, Path profileDirectory) throws IOException {
        this.browseRoot = Objects.requireNonNull(browseRoot).toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(this.browseRoot)) throw new IOException("Browse root is not a directory: " + browseRoot);
        Path requestedProfile = Objects.requireNonNull(profileDirectory).toAbsolutePath().normalize();
        Files.createDirectories(requestedProfile);
        this.profileDirectory = requestedProfile.toRealPath();
        if (this.browseRoot.equals(this.profileDirectory)) throw new IOException("Profile cannot be the browse root");
    }

    public Path browseRoot() { return browseRoot; }

    public synchronized Path createFile(Path requested) throws IOException {
        Path target = target(requested);
        return Files.createFile(target);
    }

    public synchronized Path createDirectory(Path requested) throws IOException {
        Path target = target(requested);
        return Files.createDirectory(target);
    }

    public synchronized Path move(Path requestedSource, Path requestedTarget) throws IOException {
        Path source = source(requestedSource);
        Path target = target(requestedTarget);
        rejectDescendant(source, target);
        moveContents(source, target);
        return target;
    }

    public synchronized Path copy(Path requestedSource, Path requestedTarget) throws IOException {
        Path source = source(requestedSource);
        Path target = target(requestedTarget);
        rejectDescendant(source, target);
        copyStaged(source, target);
        return target;
    }

    /** Suggests a sibling name; the subsequent copy still refuses to replace existing entries. */
    public synchronized Path availableCopyTarget(Path requestedSource) throws IOException {
        Path source = source(requestedSource);
        String name = source.getFileName().toString();
        int extension = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ? -1 : name.lastIndexOf('.');
        if (extension <= 0 || extension == name.length() - 1) extension = name.length();
        String stem = name.substring(0, extension), suffix = name.substring(extension);
        for (int number = 1; number > 0; number++) {
            String label = number == 1 ? " (copy)" : " (copy " + number + ")";
            Path candidate = source.resolveSibling(stem + label + suffix);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new IOException("Cannot find an available copy name for " + source);
    }

    /** Permanent deletion, without following symbolic links. The caller coordinates open buffers. */
    public synchronized void delete(Path requested) throws IOException {
        Path source = source(requested);
        try { deleteTree(source); }
        catch (IOException failure) {
            throw new IOException("Could not completely delete " + source + ". Some entries may already have been removed. " + failure.getMessage(), failure);
        }
    }

    private Path source(Path requested) throws IOException {
        Path path = checkedPath(requested);
        if (path.equals(browseRoot)) throw new IOException("The browse root cannot be moved, copied or deleted");
        if (profileDirectory.startsWith(path)) throw new IOException("Cannot modify a directory containing the editor profile: " + path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Path no longer exists: " + path);
        return path;
    }

    private Path target(Path requested) throws IOException {
        Path path = checkedPath(requested);
        if (path.equals(browseRoot)) throw new IOException("The browse root cannot be replaced");
        if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Parent directory does not exist: " + path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(path.toString());
        return path;
    }

    private Path checkedPath(Path requested) throws IOException {
        Objects.requireNonNull(requested);
        for (Path part : requested) if (part.toString().equals("..")) throw new IOException("Parent traversal is not allowed: " + requested);
        Path path = (requested.isAbsolute() ? requested : browseRoot.resolve(requested)).toAbsolutePath().normalize();
        if (!path.startsWith(browseRoot)) throw new IOException("Path is outside the accessible browse root: " + requested);
        if (path.startsWith(profileDirectory)) throw new IOException("The editor profile cannot be changed through file operations");
        Path current = browseRoot;
        Path parent = path.getParent();
        if (parent != null && parent.startsWith(browseRoot)) {
            for (Path part : browseRoot.relativize(parent)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw new IOException("Operations through symbolic-link directories are not allowed: " + current);
            }
            if (Files.exists(parent) && !parent.toRealPath().startsWith(browseRoot)) throw new IOException("Parent escapes the browse root: " + parent);
        }
        return path;
    }

    private static void rejectDescendant(Path source, Path target) throws IOException {
        if (target.startsWith(source)) throw new IOException("A path cannot be copied or moved into itself: " + target);
    }

    private static void moveContents(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        if (Files.getFileStore(source.getParent()).equals(Files.getFileStore(target.getParent()))) {
            Files.move(source, target);
            return;
        }
        copyStaged(source, target);
        try { deleteTree(source); }
        catch (IOException failure) {
            throw new PartialMoveException(source, target, failure);
        }
    }

    private static void copyStaged(Path source, Path target) throws IOException {
        Path staging = Files.createTempDirectory(target.getParent(), ".editor-copy-");
        try {
            Path content = staging.resolve("content");
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    Files.createDirectory(content.resolve(source.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) throw new IOException("Unsupported special file: " + file);
                    Files.copy(file, content.resolve(source.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Path copied = content.resolve(source.relativize(directory));
                    Files.setLastModifiedTime(copied, Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS));
                    PosixFileAttributeView sourceView = Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    PosixFileAttributeView targetView = Files.getFileAttributeView(copied, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (sourceView != null && targetView != null) targetView.setPermissions(sourceView.readAttributes().permissions());
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
            Files.move(content, target);
        } finally {
            deleteTree(staging);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
