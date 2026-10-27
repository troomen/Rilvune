package dev.rilvune.agents.infrastructure.acp;

import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.domain.content.AgentContent;
import dev.rilvune.agents.infrastructure.persistence.FileAgentAttachmentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AcpAttachmentsTest {
    @TempDir Path profile;
    @Test void promptsContainDurableFileResourceLinksNotMutableOriginalsOrBase64() throws Exception {
        var store = new FileAgentAttachmentStore(profile); var file = store.importContent("Source.java", "class Source {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var codec = new AcpAttachments(store); var blocks = codec.prompt(new AgentPrompt("Review this", List.of(file)));
        assertEquals("text", blocks.getFirst().get("type").getAsString());
        var part = blocks.getLast(); assertEquals("resource_link", part.get("type").getAsString());
        assertEquals(store.resolve(file).toUri().toString(), part.get("uri").getAsString()); assertEquals("Source.java", part.get("name").getAsString());
        assertFalse(part.has("data")); Files.delete(store.resolve(file)); assertThrows(Exception.class, () -> codec.prompt(new AgentPrompt("Review this", List.of(file))));
    }
    @Test void sourceProvenanceIsModelOnlyContextAndNeverReplacesTheSnapshotUri() throws Exception {
        Path source = profile.resolve("Example.java"); Files.writeString(source, "class Example {}");
        var store = new FileAgentAttachmentStore(profile); var attachment = store.importFile(source);
        var blocks = new AcpAttachments(store).prompt(new AgentPrompt("Fix this", List.of(attachment)));
        assertEquals(source.toUri().toString(), attachment.sourceUri());
        var context = blocks.get(1); assertTrue(context.get("text").getAsString().contains(source.toUri().toString()));
        assertEquals("assistant", context.getAsJsonObject("annotations").getAsJsonArray("audience").get(0).getAsString());
        assertEquals(store.resolve(attachment).toUri().toString(), blocks.getLast().get("uri").getAsString());
    }
    @Test void onlyManagedReferencesBecomeLocalPreviewsAndReplayMediaIsPersisted() throws Exception {
        var store = new FileAgentAttachmentStore(profile); var codec = new AcpAttachments(store);
        var file = store.importContent("Note.txt", "context".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var reference = new AgentContent.Resource("Note.txt", store.resolve(file).toUri().toString(), "text/plain", "");
        var converted = (AgentEvent.Content)codec.preview(new AgentEvent.Content(reference, AgentMessage.Role.USER, AgentOrigin.MAIN));
        assertInstanceOf(AgentContent.Attachment.class, converted.content());
        var unknown = new AgentEvent.Content(new AgentContent.Resource("External", "file:///etc/passwd", "text/plain", ""), AgentMessage.Role.USER, AgentOrigin.MAIN);
        assertSame(unknown, codec.preview(unknown));
        var png = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, 2), "png", png);
        var media = new AgentContent.Media("image", "image/png", Base64.getEncoder().encodeToString(png.toByteArray()), "");
        var restored = (AgentEvent.Content)codec.preview(new AgentEvent.Content(media, AgentMessage.Role.USER, AgentOrigin.MAIN));
        var attachment = assertInstanceOf(AgentContent.Attachment.class, restored.content());
        assertEquals("image/png", attachment.mimeType()); assertArrayEquals(png.toByteArray(), Files.readAllBytes(attachment.file()));
    }
}
