package dev.rilvune.agents.infrastructure.persistence;

import dev.rilvune.agents.domain.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Durable, deduplicated snapshots. No temporary paths are ever published to drafts or native history. */
public final class FileAgentAttachmentStore implements AgentAttachmentStore {
    private final Path directory;
    public FileAgentAttachmentStore(Path profile) { directory = profile.toAbsolutePath().normalize().resolve("agents/attachments"); }

    @Override public AgentAttachment importFile(Path source) throws Exception {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) throw new IOException("Choose a readable file: " + source.getFileName());
        if (Files.size(source) > AgentAttachment.MAX_BYTES) throw new IOException("Attachment exceeds 20 MiB: " + source.getFileName());
        byte[] bytes;
        try (var input = Files.newInputStream(source)) { bytes = input.readNBytes((int)AgentAttachment.MAX_BYTES + 1); }
        var saved = importContent(source.getFileName().toString(), bytes);
        return new AgentAttachment(saved.id(), saved.name(), saved.mimeType(), saved.size(), source.toAbsolutePath().normalize().toUri().toString());
    }
    @Override public AgentAttachment saveImage(byte[] png) throws Exception {
        if (!starts(png, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) throw new IOException("Clipboard image could not be encoded as PNG");
        return importContent("Pasted image.png", png);
    }
    @Override public AgentAttachment importContent(String name, byte[] bytes) throws Exception {
        if (bytes.length > AgentAttachment.MAX_BYTES) throw new IOException("Attachment exceeds 20 MiB: " + name);
        String mime = mime(bytes);
        String extension = switch (mime) { case "image/png" -> "png"; case "image/jpeg" -> "jpg"; case "image/gif" -> "gif"; case "image/webp" -> "webp"; case "application/pdf" -> "pdf"; default -> "txt"; };
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "." + extension;
        String safeName = name.replaceAll("[\\p{Cntrl}]", " ").strip(); if (safeName.length() > 255) safeName = safeName.substring(0, 255);
        AgentAttachment attachment = new AgentAttachment(id, safeName.isBlank() ? "Attachment." + extension : safeName, mime, bytes.length);
        Files.createDirectories(directory);
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) {
            Files.setPosixFilePermissions(directory.getParent(), PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        Path target = resolve(attachment);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == bytes.length) { verify(attachment); return attachment; }
        Path temporary = posix ? Files.createTempFile(directory, "attachment-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(directory, "attachment-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return attachment;
    }
    @Override public Path resolve(AgentAttachment attachment) { return directory.resolve(attachment.id()); }
    @Override public void verify(AgentAttachment attachment) throws Exception {
        Path file = resolve(attachment);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != attachment.size()) throw new IOException("Attachment is missing or changed: " + attachment.name());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count; if (total > AgentAttachment.MAX_BYTES) throw new IOException("Attachment exceeds 20 MiB"); digest.update(buffer, 0, count);
            }
        }
        if (!attachment.id().startsWith(HexFormat.of().formatHex(digest.digest()) + ".")) throw new IOException("Attachment is changed: " + attachment.name());
    }
    @Override public Optional<Path> reference(String uri) {
        try {
            Path path = Path.of(URI.create(uri)).toAbsolutePath().normalize();
            return directory.equals(path.getParent()) && path.getFileName().toString().matches("[a-f0-9]{64}\\.(png|jpg|gif|webp|pdf|txt)") ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    private static String mime(byte[] bytes) throws IOException {
        if (starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (starts(bytes, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (starts(bytes, 'G', 'I', 'F', '8', '7', 'a') || starts(bytes, 'G', 'I', 'F', '8', '9', 'a')) return "image/gif";
        if (bytes.length >= 12 && starts(bytes, 'R', 'I', 'F', 'F') && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        if (starts(bytes, '%', 'P', 'D', 'F', '-')) return "application/pdf";
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.chars().anyMatch(value -> value < 32 && value != '\n' && value != '\r' && value != '\t' && value != '\f')) throw new IOException("Unsupported binary attachment. Use UTF-8 text, PNG, JPEG, GIF, WebP or PDF.");
            return "text/plain";
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IOException("Unsupported attachment. Use UTF-8 text, PNG, JPEG, GIF, WebP or PDF.", invalid); }
    }
    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) if ((bytes[index] & 255) != signature[index]) return false;
        return true;
    }
}
