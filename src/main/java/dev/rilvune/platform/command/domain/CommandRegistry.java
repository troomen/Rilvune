package dev.rilvune.platform.command.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicitly owned by its caller. Actions execute on the calling thread. */
public final class CommandRegistry {
    private final Map<CommandId, Command> commands;

    public CommandRegistry() {
        this.commands = new LinkedHashMap<>();
    }

    public void register(Command command) {
        Objects.requireNonNull(command, "command");
        if (commands.putIfAbsent(command.id(), command) != null) {
            throw new IllegalArgumentException("Command already registered: " + command.id().value());
        }
    }

    public void execute(CommandId id) {
        Objects.requireNonNull(id, "id");
        Command command = commands.get(id);
        if (command == null) {
            throw new IllegalArgumentException("Unknown command: " + id.value());
        }
        if (command.available()) command.action().run();
    }

    public List<Command> commands() {
        return List.copyOf(commands.values());
    }
}
