package dev.editor.workspace.infrastructure;

import dev.editor.workspace.domain.TextFile;
import dev.editor.workspace.domain.Workspace;
import dev.editor.workspace.domain.WorkspaceEntry;
import dev.editor.workspace.domain.WorkspaceFiles;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioWorkspaceFilesTest {
    @Test
    void onlyExistingDirectoriesCanBeOpenedAsWorkspaces(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Path regularFile = Files.writeString(temporary.resolve("file.txt"), "text");

        assertThrows(IOException.class, () -> files.openWorkspace(temporary.resolve("missing")));
        assertThrows(IOException.class, () -> files.openWorkspace(regularFile));
        assertEquals(temporary.toRealPath(), files.openWorkspace(temporary.resolve(".")).root());
    }

    @Test
    void listsOneLevelWithDirectoriesFirstAndStableNameOrdering(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Workspace workspace = files.openWorkspace(temporary);
        Files.writeString(temporary.resolve("z.txt"), "z");
        Files.writeString(temporary.resolve("b.txt"), "b");
        Files.writeString(temporary.resolve("A.txt"), "A");
        Files.createDirectory(temporary.resolve("source"));
        Files.createDirectory(temporary.resolve(".git"));
        Files.writeString(temporary.resolve("source/nested.txt"), "nested");

        List<WorkspaceEntry> entries = files.list(workspace, Path.of("."));

        assertEquals(List.of(".git", "source", "A.txt", "b.txt", "z.txt"),
                entries.stream().map(WorkspaceEntry::name).toList());
        assertTrue(entries.getFirst().directory());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }

    @Test
    void preservesUtf8TextAndLineEndings(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Workspace workspace = files.openWorkspace(temporary);
        String contents = "\uFEFFПривет, editor 🧭\r\n\tsecond line\n";
        Path path = Files.writeString(temporary.resolve("notes.txt"), contents, StandardCharsets.UTF_8);

        TextFile preview = files.read(workspace, Path.of("notes.txt"));

        assertEquals(path.toRealPath(), preview.path());
        assertEquals(contents, preview.text());
    }

    @Test
    void previewLimitCountsBytesAndAcceptsFilesExactlyAtTheLimit(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(6);
        Workspace workspace = files.openWorkspace(temporary);
        Files.writeString(temporary.resolve("exact.txt"), "ééé", StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("large.txt"), "éééé", StandardCharsets.UTF_8);

        assertEquals("ééé", files.read(workspace, Path.of("exact.txt")).text());
        IOException exception = assertThrows(IOException.class,
                () -> files.read(workspace, Path.of("large.txt")));
        assertTrue(exception.getMessage().contains("6 bytes"));
    }

    @Test
    void rejectsInvalidUtf8AndBinaryControlCharacters(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Workspace workspace = files.openWorkspace(temporary);
        Files.write(temporary.resolve("invalid.bin"), new byte[] {(byte) 0xC3, (byte) 0x28});
        Files.write(temporary.resolve("binary.bin"), new byte[] {'t', 'e', 'x', 't', 0});
        Files.write(temporary.resolve("control.bin"), new byte[] {1, 2, 3});

        assertTrue(assertThrows(IOException.class,
                () -> files.read(workspace, Path.of("invalid.bin"))).getMessage().contains("UTF-8"));
        assertTrue(assertThrows(IOException.class,
                () -> files.read(workspace, Path.of("binary.bin"))).getMessage().contains("Binary"));
        assertThrows(IOException.class, () -> files.read(workspace, Path.of("control.bin")));
    }

    @Test
    void rejectsParentTraversalAndSiblingPathsWithTheSamePrefix(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        Path sibling = Files.createDirectory(temporary.resolve("workspace-other"));
        Path external = Files.writeString(sibling.resolve("private.txt"), "outside");
        Workspace workspace = files.openWorkspace(root);

        assertThrows(IOException.class, () -> files.read(workspace, Path.of("../workspace-other/private.txt")));
        assertThrows(IOException.class, () -> files.read(workspace, external));
        assertThrows(IOException.class, () -> files.list(workspace, sibling));
    }

    @Test
    void symbolicLinksCannotReadOrListOutsideTheWorkspace(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path external = Files.writeString(outside.resolve("private.txt"), "outside");
        Workspace workspace = files.openWorkspace(root);
        Files.createSymbolicLink(root.resolve("file-link.txt"), external);
        Files.createSymbolicLink(root.resolve("directory-link"), outside);

        assertThrows(IOException.class, () -> files.read(workspace, Path.of("file-link.txt")));
        assertThrows(IOException.class, () -> files.list(workspace, Path.of("directory-link")));
        assertThrows(IOException.class, () -> files.read(workspace, Path.of("directory-link/private.txt")));
    }

    @Test
    void symbolicLinksWithinTheWorkspaceRemainUsable(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path original = Files.writeString(source.resolve("notes.txt"), "inside");
        Files.createSymbolicLink(temporary.resolve("source-link"), source);
        Files.createSymbolicLink(temporary.resolve("file-link.txt"), original);
        Workspace workspace = files.openWorkspace(temporary);

        assertEquals("inside", files.read(workspace, Path.of("file-link.txt")).text());
        assertEquals(original.toRealPath(), files.read(workspace, Path.of("source-link/notes.txt")).path());
        assertEquals(List.of("notes.txt"),
                files.list(workspace, Path.of("source-link")).stream().map(WorkspaceEntry::name).toList());
    }

    @Test
    void rejectsDirectoriesAndMissingFilesAsTextPreviews(@TempDir Path temporary) throws IOException {
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Workspace workspace = files.openWorkspace(temporary);

        assertThrows(IOException.class, () -> files.read(workspace, Path.of(".")));
        assertThrows(IOException.class, () -> files.read(workspace, Path.of("missing.txt")));
    }

    @Test
    void rejectsUnreadableFilesWithAnActionableMessage(@TempDir Path temporary) throws IOException {
        Assumptions.assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        WorkspaceFiles files = new NioWorkspaceFiles(1024);
        Workspace workspace = files.openWorkspace(temporary);
        Path unreadable = Files.writeString(temporary.resolve("unreadable.txt"), "private");
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(unreadable);
        try {
            Files.setPosixFilePermissions(unreadable, Set.of());
            Assumptions.assumeFalse(Files.isReadable(unreadable), "Process bypasses filesystem permissions");

            IOException exception = assertThrows(IOException.class, () -> files.read(workspace, unreadable));

            assertTrue(exception.getMessage().contains("not readable"));
        } finally {
            Files.setPosixFilePermissions(unreadable, originalPermissions);
        }
    }
}
