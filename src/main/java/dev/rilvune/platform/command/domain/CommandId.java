package dev.rilvune.platform.command.domain;

import java.util.Objects;

public record CommandId(String value) {
    public CommandId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Command ID must not be blank");
        }
    }
}
