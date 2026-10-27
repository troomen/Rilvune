package dev.editor.workspace.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationsTest {
    @TempDir Path temporary;

    @Test void projectCreationOnlyCreatesTheRequestedPlainDirectory() throws Exception {
        WorkspaceOperations operations = operations();
        Path project = operations.createDirectory(Path.of("my project"));
        assertEquals(temporary.resolve("projects/my project"), project);
        try (var entries = Files.list(project)) { assertEquals(0, entries.count()); }
        assertThrows(FileAlreadyExistsException.class, () -> operations.createDirectory(project));
    }

    @Test void createAndMoveDoNotReplaceExistingContents() throws Exception {
        WorkspaceOperations operations = operations();
        Path first = operations.createFile(Path.of("first.txt"));
        Path second = operations.createFile(Path.of("second.txt"));
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        assertThrows(FileAlreadyExistsException.class, () -> operations.createFile(second));
        assertThrows(FileAlreadyExistsException.class, () -> operations.move(first, second));
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
    }

    @Test void recursiveCopyPreservesFilesAndMovesFolderWithoutChangingItsChildren() throws Exception {
        WorkspaceOperations operations = operations();
        Path source = operations.createDirectory(Path.of("source"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Files.writeString(nested.resolve("example.txt"), "important text");
        Path copy = operations.copy(source, Path.of("copy"));
        assertEquals("important text", Files.readString(copy.resolve("nested/example.txt")));
        Path renamed = operations.move(copy, Path.of("renamed"));
        assertFalse(Files.exists(copy));
        assertTrue(Files.exists(source));
        assertEquals("important text", Files.readString(renamed.resolve("nested/example.txt")));
    }

    @Test void siblingCopiesKeepExtensionsAndSkipExistingFilesAndDanglingLinks() throws Exception {
        WorkspaceOperations operations = operations();
        Path source = operations.createFile(Path.of("notes.txt")); Files.writeString(source, "original");
        Path first = operations.availableCopyTarget(source);
        assertEquals(source.resolveSibling("notes (copy).txt"), first);
        Files.createSymbolicLink(first, Path.of("missing"));
        Path occupied = Files.writeString(source.resolveSibling("notes (copy 2).txt"), "keep this");
        Path destination = operations.availableCopyTarget(source);
        assertEquals(source.resolveSibling("notes (copy 3).txt"), destination);
        operations.copy(source, destination);
        assertEquals("original", Files.readString(source)); assertEquals("original", Files.readString(destination));
        assertEquals("keep this", Files.readString(occupied)); assertTrue(Files.isSymbolicLink(first));
    }

    @Test void siblingCopyNamesHandleDotfilesExtensionlessFilesAndDottedDirectories() throws Exception {
        WorkspaceOperations operations = operations();
        for (String name : java.util.List.of(".env", "README")) {
            Path source = operations.createFile(Path.of(name));
            Path copied = operations.copy(source, operations.availableCopyTarget(source));
            assertEquals(source.resolveSibling(name + " (copy)"), copied);
            assertTrue(Files.exists(source)); assertTrue(Files.exists(copied));
        }
        Path directory = operations.createDirectory(Path.of("project.v1"));
        Files.writeString(directory.resolve("inside.txt"), "kept");
        Path copied = operations.copy(directory, operations.availableCopyTarget(directory));
        assertEquals(directory.resolveSibling("project.v1 (copy)"), copied);
        assertEquals("kept", Files.readString(copied.resolve("inside.txt")));
    }

    @Test void refusesTraversalOutsideBrowseRootAndDirectoryDescendantMoves() throws Exception {
        WorkspaceOperations operations = operations();
        Path source = operations.createDirectory(Path.of("source"));
        assertThrows(IOException.class, () -> operations.createFile(temporary.resolve("outside.txt")));
        assertThrows(IOException.class, () -> operations.createFile(Path.of("source/../traversal.txt")));
        assertThrows(IOException.class, () -> operations.move(source, source.resolve("inside")));
        assertThrows(IOException.class, () -> operations.copy(source, source.resolve("inside")));
        assertThrows(IOException.class, () -> operations.delete(operations.browseRoot()));
        assertTrue(Files.isDirectory(source));
    }

    @Test void symbolicLinkLeavesStayLinksAndNeverTraverseExternalTrees() throws Exception {
        WorkspaceOperations operations = operations();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path protectedFile = Files.writeString(outside.resolve("protected.txt"), "outside");
        Path source = operations.createDirectory(Path.of("source"));
        Path link = Files.createSymbolicLink(source.resolve("linked"), outside);
        Path copy = operations.copy(source, Path.of("copy"));
        assertTrue(Files.isSymbolicLink(copy.resolve("linked")));
        assertEquals(outside, Files.readSymbolicLink(copy.resolve("linked")));
        assertThrows(IOException.class, () -> operations.createFile(link.resolve("new.txt")));
        assertThrows(IOException.class, () -> operations.delete(link.resolve("protected.txt")));
        operations.delete(link);
        assertEquals("outside", Files.readString(protectedFile));
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
    }

    @Test void danglingLinksCountAsExistingTargets() throws Exception {
        WorkspaceOperations operations = operations();
        Path source = operations.createFile(Path.of("source"));
        Path existing = Files.createSymbolicLink(operations.browseRoot().resolve("existing"), Path.of("missing"));
        assertThrows(FileAlreadyExistsException.class, () -> operations.copy(source, existing));
        assertTrue(Files.exists(existing, LinkOption.NOFOLLOW_LINKS));
    }

    @Test void deleteRemovesDirectoryContentsWithoutCreatingTrash() throws Exception {
        WorkspaceOperations operations = operations();
        Path folder = operations.createDirectory(Path.of("project"));
        Files.writeString(folder.resolve("кириллица.txt"), "Text");
        operations.delete(folder);
        assertFalse(Files.exists(folder));
        try (var entries = Files.list(temporary.resolve("profile"))) { assertEquals(0, entries.count()); }
    }

    @Test void cannotOperateOnProfileOrItsContainingDirectory() throws Exception {
        Path browse = Files.createDirectory(temporary.resolve("projects"));
        Path profile = browse.resolve("profile");
        WorkspaceOperations operations = new WorkspaceOperations(browse, profile);
        assertThrows(IOException.class, () -> operations.createFile(profile.resolve("manual.txt")));
        assertThrows(IOException.class, () -> operations.delete(profile));
    }

    @Test void preservesExecutableFilePermissions() throws Exception {
        WorkspaceOperations operations = operations();
        Path executable = operations.createFile(Path.of("script.sh"));
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(executable, permissions);
        Path copy = operations.copy(executable, Path.of("copy.sh"));
        assertEquals(permissions, Files.getPosixFilePermissions(copy));
    }

    private WorkspaceOperations operations() throws IOException {
        Path browse = Files.createDirectories(temporary.resolve("projects"));
        return new WorkspaceOperations(browse, temporary.resolve("profile"));
    }
}
