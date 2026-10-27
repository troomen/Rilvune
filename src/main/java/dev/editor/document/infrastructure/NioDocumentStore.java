package dev.editor.document.infrastructure;

import dev.editor.document.domain.DiskSnapshot;
import dev.editor.document.domain.DocumentStore;
import dev.editor.document.domain.FileChangedException;
import dev.editor.document.domain.LineEnding;
import dev.editor.document.domain.TextFormat;
import dev.editor.document.domain.TextMerge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** UTF-8 persistence with optimistic conflict detection and atomic replacement. */
public final class NioDocumentStore implements DocumentStore {
    private final int maxBytes;
    private final Path lockDirectory;
    private final Path allowedRoot;

    public NioDocumentStore(int maxBytes) {
        this(maxBytes, null);
    }

    /** A persistent lock directory coordinates application instances without adding files to projects. */
    public NioDocumentStore(int maxBytes, Path lockDirectory) {
        this(maxBytes, lockDirectory, null);
    }

    public NioDocumentStore(int maxBytes, Path lockDirectory, Path allowedRoot) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid size limit");
        this.maxBytes = maxBytes;
        this.lockDirectory = lockDirectory == null ? null : lockDirectory.toAbsolutePath().normalize();
        this.allowedRoot = allowedRoot == null ? null : allowedRoot.toAbsolutePath().normalize();
    }

    @Override
    public Path canonicalPath(Path path) throws IOException {
        Path canonical = path.toAbsolutePath().normalize().toRealPath();
        checkScope(canonical);
        return canonical;
    }

    @Override
    public DiskSnapshot read(Path path) throws IOException {
        checkScope(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.NoSuchFileException(path.toString());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("File is no longer a regular file (or was replaced by a link): " + path);
        }
        if (Files.size(path) > maxBytes) throw sizeError(path);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes) throw sizeError(path);
        return decode(path, bytes);
    }

    @Override
    public DiskSnapshot write(Path path, String text, TextFormat format, DiskSnapshot expected) throws IOException {
        return writeWithLock(path, text, format, expected, expected);
    }

    @Override
    public DiskSnapshot createCopy(Path path, String text, TextFormat format, DiskSnapshot source) throws IOException {
        return writeWithLock(path, text, format, null, source);
    }

    private DiskSnapshot writeWithLock(Path path, String text, TextFormat format, DiskSnapshot expected, DiskSnapshot source) throws IOException {
        checkScope(path);
        if (lockDirectory == null) return writeUnlocked(path, text, format, expected, source);
        Files.createDirectories(lockDirectory);
        String lockName = fingerprint(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(lockDirectory.resolve(lockName + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            while (true) {
                try { lock = channel.lock(); break; }
                catch (java.nio.channels.OverlappingFileLockException exception) {
                    // Another service in this JVM owns the same operating-system lock.
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted while waiting for file lock", exception);
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
                }
            }
            java.nio.channels.FileLock acquired = lock;
            try (acquired) { return writeUnlocked(path, text, format, expected, source); }
        }
    }

    private DiskSnapshot writeUnlocked(Path path, String text, TextFormat format, DiskSnapshot expected, DiskSnapshot source) throws IOException {
        byte[] bytes = encode(text, format, source);
        if (bytes.length > maxBytes) throw sizeError(path);
        DiskSnapshot prepared = decode(path, bytes);
        if (!prepared.text().contains("\n") && format.lineEnding() != LineEnding.MIXED) {
            prepared = new DiskSnapshot(prepared.text(), prepared.rawText(), format, prepared.fingerprint());
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Parent directory does not exist: " + path);
        if (expected != null) {
            checkExpected(path, expected);
            if (!Files.isWritable(path)) throw new IOException("File is not writable: " + path);
        } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(path.toString());
        }
        Path temporary = Files.createTempFile(parent, ".editor-write-", ".tmp");
        try {
            if (expected != null && Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                Files.setPosixFilePermissions(temporary, permissions);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            if (expected == null) {
                checkScope(path);
                // Without REPLACE_EXISTING, a colliding new file is never silently overwritten.
                Files.move(temporary, path);
            } else {
                checkScope(path);
                checkExpected(path, expected);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    checkExpected(path, expected);
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return prepared;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void checkExpected(Path path, DiskSnapshot expected) throws IOException {
        DiskSnapshot actual = read(path);
        if (!actual.fingerprint().equals(expected.fingerprint())) throw new FileChangedException(path);
    }

    private void checkScope(Path path) throws IOException {
        if (allowedRoot == null) return;
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path root = allowedRoot.toRealPath();
        Path realCandidate = parent == null ? normalized.toRealPath() : parent.toRealPath().resolve(normalized.getFileName());
        if (!realCandidate.startsWith(root)) throw new IOException("File is outside the available folder: " + path);
    }

    private IOException sizeError(Path path) {
        return new IOException("File exceeds the editable limit of " + maxBytes / (1024 * 1024) + " MiB (" + maxBytes + " bytes): " + path);
    }

    private static DiskSnapshot decode(Path path, byte[] bytes) throws IOException {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("File is not valid UTF-8 text: " + path, exception);
        }
        var normalized = dev.editor.platform.text.domain.NormalizedText.from(decoded);
        String raw = normalized.byteOrderMark() ? decoded.substring(1) : decoded;
        return new DiskSnapshot(normalized.text(), raw,
                new TextFormat(normalized.byteOrderMark(), LineEnding.valueOf(normalized.lineEnding())), fingerprint(bytes));
    }

    private static byte[] encode(String text, TextFormat format, DiskSnapshot expected) throws IOException {
        String raw = format.lineEnding() == LineEnding.MIXED && expected != null
                ? updateMixedSeparators(text, expected)
                : text.replace("\n", format.lineEnding().separator());
        String encoded = format.byteOrderMark() ? "\uFEFF" + raw : raw;
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(encoded));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new IOException("Text contains an incomplete Unicode character; the file was not changed", exception);
        }
    }

    private static String updateMixedSeparators(String text, DiskSnapshot expected) {
        List<TextMerge.Change> changes = TextMerge.changes(expected.text(), text);
        int[] rawOffsets = new int[expected.text().length() + 1];
        int logical = 0;
        for (int raw = 0; raw < expected.rawText().length(); raw++) {
            rawOffsets[logical++] = raw;
            if (expected.rawText().charAt(raw) == '\r' && raw + 1 < expected.rawText().length()
                    && expected.rawText().charAt(raw + 1) == '\n') raw++;
        }
        rawOffsets[logical] = expected.rawText().length();
        String separator = expected.rawText().contains("\r\n") ? "\r\n" : "\n";
        StringBuilder result = new StringBuilder(expected.rawText());
        for (int i = changes.size() - 1; i >= 0; i--) {
            TextMerge.Change change = changes.get(i);
            result.replace(rawOffsets[change.start()], rawOffsets[change.end()], change.replacement().replace("\n", separator));
        }
        return result.toString();
    }

    private static String fingerprint(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
