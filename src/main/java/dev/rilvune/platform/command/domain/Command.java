package dev.rilvune.platform.command.domain;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record Command(CommandId id, String title, Runnable action, BooleanSupplier availability) {
    public Command(CommandId id, String title, Runnable action) { this(id, title, action, () -> true); }
    public boolean available() { return availability.getAsBoolean(); }
    public Command {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(availability, "availability");
        if (title.isBlank()) {
            throw new IllegalArgumentException("Command title must not be blank");
        }
    }
}
