package dev.rilvune.platform.layout.domain;

import java.util.Objects;

public record Panel(String id) implements LayoutNode {
    public Panel {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Panel ID must not be blank");
        }
    }
}
