package dev.editor.workspace.infrastructure;

import dev.editor.workspace.domain.TextFile;
import dev.editor.workspace.domain.Workspace;
import dev.editor.workspace.domain.WorkspaceEntry;
import dev.editor.workspace.domain.WorkspaceFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class NioWorkspaceFiles implements WorkspaceFiles {
    private static final Comparator<WorkspaceEntry> ENTRY_ORDER = Comparator
            .comparing(WorkspaceEntry::directory).reversed()
            .thenComparing(WorkspaceEntry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(WorkspaceEntry::name);

    private final int maxPreviewBytes;

    public NioWorkspaceFiles(int maxPreviewBytes) {
        if (maxPreviewBytes <= 0 || maxPreviewBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Preview byte limit must be between 1 and Integer.MAX_VALUE - 1");
        }
        this.maxPreviewBytes = maxPreviewBytes;
    }

    @Override
    public Workspace openWorkspace(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path realRoot = root.toAbsolutePath().normalize().toRealPath();
        requireDirectory(realRoot);
        return new Workspace(realRoot);
    }

    @Override
    public List<WorkspaceEntry> list(Workspace workspace, Path directory) throws IOException {
        Path realDirectory = resolveWithin(workspace, directory);
        requireDirectory(realDirectory);
        List<WorkspaceEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(realDirectory)) {
            for (Path child : children) {
                entries.add(new WorkspaceEntry(child, child.getFileName().toString(), Files.isDirectory(child)));
            }
        } catch (DirectoryIteratorException exception) {
            throw exception.getCause();
        }
        entries.sort(ENTRY_ORDER);
        return List.copyOf(entries);
    }

    @Override
    public TextFile read(Workspace workspace, Path file) throws IOException {
        Path realFile = resolveWithin(workspace, file);
        if (!Files.isRegularFile(realFile)) {
            throw new IOException("Only regular text files can be previewed: " + file);
        }
        if (!Files.isReadable(realFile)) {
            throw new IOException("File is not readable: " + file);
        }
        if (Files.size(realFile) > maxPreviewBytes) {
            throw previewLimitExceeded(file);
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(realFile, LinkOption.NOFOLLOW_LINKS)) {
            // Reading one extra byte also bounds the preview if a file grows after the size check.
            bytes = input.readNBytes(maxPreviewBytes + 1);
        }
        if (bytes.length > maxPreviewBytes) {
            throw previewLimitExceeded(file);
        }
        String text = decodeText(realFile, bytes);
        return new TextFile(realFile, text);
    }

    @Override public Path canonicalFile(Workspace workspace, Path file) throws IOException {
        Path path = resolveWithin(workspace, file);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IOException("Choose a readable regular text file: " + file);
        return path;
    }

    private Path resolveWithin(Workspace workspace, Path requested) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(requested, "requested");
        Path root = workspace.root();
        Path candidate = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
        if (!candidate.startsWith(root)) {
            throw new dev.editor.workspace.domain.OutsideWorkspaceException(requested);
        }
        Path realRoot = root.toRealPath();
        requireDirectory(realRoot);
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new dev.editor.workspace.domain.OutsideWorkspaceException(requested);
        }
        return realCandidate;
    }

    private void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Workspace path is not a directory: " + directory);
        }
        if (!Files.isReadable(directory)) {
            throw new IOException("Directory is not readable: " + directory);
        }
    }

    private IOException previewLimitExceeded(Path file) {
        return new IOException("File exceeds the preview limit of " + maxPreviewBytes + " bytes: " + file);
    }

    private String decodeText(Path file, byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("File is not valid UTF-8 text: " + file, exception);
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t' && character != '\f') {
                throw new IOException("Binary files cannot be previewed as text: " + file);
            }
        }
        return text;
    }
}
