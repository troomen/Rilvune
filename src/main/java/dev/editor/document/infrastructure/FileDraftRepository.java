package dev.editor.document.infrastructure;

import dev.editor.document.domain.DiskSnapshot;
import dev.editor.document.domain.DraftRepository;
import dev.editor.document.domain.LineEnding;
import dev.editor.document.domain.RecoveryDraft;
import dev.editor.document.domain.SyncState;
import dev.editor.document.domain.TextFormat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Versioned recovery records; writes replace a complete record, never truncate the previous one. */
public final class FileDraftRepository implements DraftRepository {
    private static final int MAGIC = 0x45445231;
    private static final int MAX_STRING_CHARACTERS = 32 * 1024 * 1024;
    private final Path directory;
    private final int maxStringCharacters;
    private final Publication publication;
    @FunctionalInterface interface Publication { void replace(Path temporary, Path target) throws IOException; }
    private final java.util.Map<UUID, Claim> claims = new java.util.HashMap<>();
    private record Claim(FileChannel channel, java.nio.channels.FileLock lock) { }

    @Override public synchronized boolean claim(UUID id) throws IOException {
        if (claims.containsKey(id)) return true;
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(id + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            java.nio.channels.FileLock lock = channel.tryLock();
            if (lock == null) { channel.close(); return false; }
            claims.put(id, new Claim(channel, lock)); return true;
        } catch (java.nio.channels.OverlappingFileLockException busy) { channel.close(); return false; }
        catch (IOException failure) { channel.close(); throw failure; }
    }
    @Override public synchronized void release(UUID id) throws IOException {
        Claim claim = claims.remove(id);
        if (claim != null) { try { claim.lock().release(); } finally { claim.channel().close(); } }
    }
    private void requireClaim(UUID id) throws IOException {
        if (!claim(id)) throw new IOException("This recovery document is in use by another editor instance: " + id);
    }

    public FileDraftRepository(Path directory) { this(directory, MAX_STRING_CHARACTERS); }

    FileDraftRepository(Path directory, int maxStringCharacters) {
        this(directory, maxStringCharacters, (temporary, target) -> Files.move(temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    FileDraftRepository(Path directory, int maxStringCharacters, Publication publication) {
        if (maxStringCharacters < 1) throw new IllegalArgumentException("Recovery string limit must be positive");
        this.directory = directory.toAbsolutePath().normalize();
        this.maxStringCharacters = maxStringCharacters;
        this.publication = java.util.Objects.requireNonNull(publication);
    }

    @Override
    public void save(RecoveryDraft draft) throws IOException {
        validate(draft); // Reject unreadable proposals before touching a previous checkpoint or claiming storage.
        requireClaim(draft.id());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".draft-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeLong(draft.id().getMostSignificantBits());
                output.writeLong(draft.id().getLeastSignificantBits());
                writeString(output, draft.path() == null ? null : draft.path().toString());
                writeString(output, draft.text());
                output.writeLong(draft.revision());
                writeFormat(output, draft.format());
                writeSnapshot(output, draft.base());
                writeSnapshot(output, draft.external());
                output.writeUTF(draft.state().name());
                writeString(output, draft.message());
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            publication.replace(temporary, path(draft.id()));
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override
    public List<RecoveryDraft> load() throws IOException { return scan().drafts(); }

    @Override
    public Scan scan() throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return new Scan(List.of(), List.of()); }
        if (!attributes.isDirectory()) throw new IOException("Recovery location is not a directory: " + directory);
        List<RecoveryDraft> records = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory, "*.draft")) {
            for (Path path : paths) {
                try { records.add(read(path)); }
                catch (NoSuchFileException removed) { /* Autosave can remove an entry after enumeration. */ }
                catch (IOException failure) { problems.add(new Problem(path, failure.getMessage())); }
            }
        } catch (DirectoryIteratorException failure) {
            problems.add(new Problem(directory, "Recovery scan was incomplete: " + failure.getCause().getMessage()));
        } catch (IOException failure) {
            problems.add(new Problem(directory, "Recovery directory could not be read completely: " + failure.getMessage()));
        }
        return new Scan(records, problems);
    }

    @Override
    public java.util.Optional<RecoveryDraft> find(UUID id) throws IOException {
        try { return java.util.Optional.of(read(path(id))); }
        catch (java.nio.file.NoSuchFileException removed) { return java.util.Optional.empty(); }
    }

    private RecoveryDraft read(Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Not a regular recovery file");
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 DataInputStream input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)))) {
                // The size and bytes must belong to the same opened file, even across an atomic replacement.
                long recordBytes = channel.size();
                if (input.readInt() != MAGIC) throw new IOException("Unsupported recovery record");
                UUID id = new UUID(input.readLong(), input.readLong());
                if (!path.getFileName().toString().equals(id + ".draft")) throw new IOException("Recovery identity does not match its file");
                String file = readString(input, recordBytes, false);
                RecoveryDraft draft = new RecoveryDraft(id, file == null ? null : Path.of(file), readString(input, recordBytes, true), input.readLong(),
                        readFormat(input), readSnapshot(input, recordBytes), readSnapshot(input, recordBytes),
                        SyncState.valueOf(input.readUTF()), readString(input, recordBytes, false));
                if (input.read() != -1) throw new IOException("Unexpected data after recovery record");
                return draft;
            }
        } catch (NoSuchFileException removed) { throw removed; }
        catch (IOException | RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            throw new IOException("Cannot read recovery record " + path + ": " + message, failure);
        }
    }

    @Override
    public void remove(UUID id) throws IOException { requireClaim(id); Files.deleteIfExists(path(id)); }

    private Path path(UUID id) { return directory.resolve(id + ".draft"); }

    private static void writeSnapshot(DataOutputStream output, DiskSnapshot snapshot) throws IOException {
        output.writeBoolean(snapshot != null);
        if (snapshot == null) return;
        writeString(output, snapshot.text());
        writeString(output, snapshot.rawText());
        writeFormat(output, snapshot.format());
        writeString(output, snapshot.fingerprint());
    }

    private DiskSnapshot readSnapshot(DataInputStream input, long recordBytes) throws IOException {
        return input.readBoolean() ? new DiskSnapshot(readString(input, recordBytes, true), readString(input, recordBytes, true),
                readFormat(input), readString(input, recordBytes, true)) : null;
    }

    private static void writeFormat(DataOutputStream output, TextFormat format) throws IOException {
        output.writeBoolean(format.byteOrderMark());
        output.writeUTF(format.lineEnding().name());
    }

    private static TextFormat readFormat(DataInputStream input) throws IOException {
        return new TextFormat(input.readBoolean(), LineEnding.valueOf(input.readUTF()));
    }

    private void validate(RecoveryDraft draft) throws IOException {
        if (draft == null || draft.id() == null || draft.format() == null || draft.state() == null) {
            throw new IOException("Recovery identity, format and state are required");
        }
        validateString(draft.path() == null ? null : draft.path().toString(), false);
        validateString(draft.text(), true);
        validateString(draft.message(), false);
        validateSnapshot(draft.base()); validateSnapshot(draft.external());
    }
    private void validateSnapshot(DiskSnapshot snapshot) throws IOException {
        if (snapshot == null) return;
        validateString(snapshot.text(), true);
        validateString(snapshot.rawText(), true);
        validateString(snapshot.fingerprint(), true);
    }
    private void validateString(String text, boolean required) throws IOException {
        if (text == null) { if (required) throw new IOException("Required recovery string is missing"); return; }
        checkLength(text.length());
    }
    private void checkLength(int size) throws IOException {
        if (size < 0 || size > maxStringCharacters) throw new IOException(
                "Recovery string length " + size + " is outside the supported limit of " + maxStringCharacters + " UTF-16 units");
    }

    private static void writeString(DataOutputStream output, String text) throws IOException {
        output.writeInt(text == null ? -1 : text.length());
        // UTF-16 also retains an incomplete surrogate while the user is still entering text.
        if (text != null) output.writeChars(text);
    }

    private String readString(DataInputStream input, long recordBytes, boolean required) throws IOException {
        int size = input.readInt();
        if (size == -1 && !required) return null;
        checkLength(size);
        if (2L * size > recordBytes) throw new IOException("Recovery string is larger than the record");
        char[] text = new char[size];
        for (int i = 0; i < size; i++) text[i] = input.readChar();
        return new String(text);
    }
}
