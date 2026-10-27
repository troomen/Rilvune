package dev.rilvune.agents.domain;

import java.nio.file.Path;
import java.util.Optional;

/** Private attachment storage. Import and verification are blocking worker operations. */
public interface AgentAttachmentStore {
    AgentAttachment importFile(Path source) throws Exception;
    AgentAttachment saveImage(byte[] png) throws Exception;
    AgentAttachment importContent(String name, byte[] contents) throws Exception;
    Path resolve(AgentAttachment attachment);
    void verify(AgentAttachment attachment) throws Exception;
    /** Recognizes only this store's own file URIs; never fetches arbitrary resource targets. */
    Optional<Path> reference(String uri);
}
