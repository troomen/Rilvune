package dev.rilvune.platform.command.domain;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandRegistryTest {
    @Test
    void duplicateRegistrationCannotReplaceAnExistingAction() {
        CommandRegistry registry = new CommandRegistry();
        CommandId id = new CommandId("workspace.open");
        List<String> executed = new ArrayList<>();
        registry.register(new Command(id, "Open workspace", () -> executed.add("original")));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new Command(id, "Replacement", () -> executed.add("replacement"))));
        registry.execute(id);

        assertEquals(List.of("original"), executed);
    }

    @Test
    void commandDiscoveryIsAnImmutableSnapshotInRegistrationOrder() {
        CommandRegistry registry = new CommandRegistry();
        Command first = new Command(new CommandId("workspace.open"), "Open", () -> { });
        Command second = new Command(new CommandId("palette.open"), "Commands", () -> { });
        registry.register(first);
        List<Command> earlierSnapshot = registry.commands();
        registry.register(second);

        assertEquals(List.of(first), earlierSnapshot);
        assertEquals(List.of(first, second), registry.commands());
        assertThrows(UnsupportedOperationException.class, () -> earlierSnapshot.clear());
    }

    @Test
    void unknownCommandReportsTheMissingIdentifier() {
        CommandRegistry registry = new CommandRegistry();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registry.execute(new CommandId("missing.command")));

        assertEquals("Unknown command: missing.command", exception.getMessage());
    }
    @org.junit.jupiter.api.Test void availabilityIsRecheckedAtExecutionTime() {
        var enabled = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new CommandRegistry(); var id = new CommandId("dynamic");
        registry.register(new Command(id, "Dynamic", calls::incrementAndGet, enabled::get));
        registry.execute(id); org.junit.jupiter.api.Assertions.assertEquals(0, calls.get());
        enabled.set(true); registry.execute(id); org.junit.jupiter.api.Assertions.assertEquals(1, calls.get());
    }

}
