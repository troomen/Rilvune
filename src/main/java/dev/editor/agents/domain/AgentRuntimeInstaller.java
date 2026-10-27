package dev.editor.agents.domain;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public interface AgentRuntimeInstaller {
    Optional<Path> installed() throws Exception;
    Path install(Consumer<String> progress) throws Exception;
}
