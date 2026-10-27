package dev.rilvune.agents.infrastructure.acp;

import com.google.gson.JsonObject;
import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.domain.content.AgentContent;
import java.io.IOException;
import java.util.*;
import static dev.rilvune.agents.infrastructure.AgentJson.object;

/** ACP prompt encoding and trusted local history previews. No arbitrary resource URI is read. */
public final class AcpAttachments {
    private final AgentAttachmentStore store;
    public AcpAttachments(AgentAttachmentStore store) { this.store = store; }
    public List<JsonObject> prompt(AgentPrompt prompt) throws Exception {
        List<JsonObject> blocks = new ArrayList<>();
        if (!prompt.text().isBlank()) blocks.add(object("type", "text", "text", prompt.text()));
        if (!prompt.attachments().isEmpty()) {
            StringBuilder context = new StringBuilder("Attached files are immutable context snapshots stored in the editor profile, not editable project files. Do not modify these private snapshots.");
            for (var attachment : prompt.attachments()) if (!attachment.sourceUri().isBlank())
                context.append("\nOriginal location of ").append(attachment.name()).append(": ").append(attachment.sourceUri());
            context.append("\nWhen asked to edit an attached source file, use its original location and inspect its current contents before changing it.");
            blocks.add(object("type", "text", "text", context.toString(), "annotations", object("audience", List.of("assistant"))));
        }
        for (var attachment : prompt.attachments()) {
            store.verify(attachment);
            blocks.add(object("type", "resource_link", "uri", store.resolve(attachment).toUri().toString(), "name", attachment.name(), "mimeType", attachment.mimeType(), "size", attachment.size()));
        }
        return List.copyOf(blocks);
    }
    public AgentEvent preview(AgentEvent event) {
        if (!(event instanceof AgentEvent.Content content) || content.role() != AgentMessage.Role.USER) return event;
        AgentContent block = content.content();
        if (block instanceof AgentContent.Resource resource) {
            var path = store.reference(resource.uri());
            if (path.isPresent()) return new AgentEvent.Content(new AgentContent.Attachment(resource.name().isBlank() ? "Attached file" : resource.name(), resource.mimeType(), path.orElseThrow()), content.role(), content.origin());
        }
        if (block instanceof AgentContent.Media media && !media.data().isBlank()) {
            // Replayed embedded media is persisted and released from the bounded transcript projection.
            try {
                if (media.data().length() > (AgentAttachment.MAX_BYTES * 4 / 3) + 4) throw new IOException("Saved media exceeds 20 MiB");
                var attachment = store.importContent("Attached " + (media.kind().equals("image") ? "image" : "file"), Base64.getDecoder().decode(media.data()));
                return new AgentEvent.Content(new AgentContent.Attachment(attachment.name(), attachment.mimeType(), store.resolve(attachment)), content.role(), content.origin());
            } catch (Exception invalid) {
                return new AgentEvent.Content(new AgentContent.Unknown("attachment", "Attachment preview unavailable: " + Objects.toString(invalid.getMessage(), "Invalid media")), content.role(), content.origin());
            }
        }
        return event;
    }
}
