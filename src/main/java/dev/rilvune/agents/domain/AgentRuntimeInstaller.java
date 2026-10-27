package dev.rilvune.agents.domain;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public interface AgentRuntimeInstaller {
    /** A permanent platform limitation, checked before filesystem or network activity. */
    default Optional<String> unsupportedReason() { return Optional.empty(); }
    Optional<Path> installed() throws Exception;
    Path install(Consumer<String> progress) throws Exception;
}
