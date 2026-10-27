package dev.rilvune.editor.application;

import dev.rilvune.session.domain.TabDescriptor;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Explicit composition, typed factories; no reflection, plugins or untyped payloads. */
public final class EditorTabRestorers {
    private final Map<Class<?>, Function<TabDescriptor, CompletableFuture<String>>> factories = new HashMap<>();
    public <D extends TabDescriptor> void register(Class<D> type, Function<D, CompletableFuture<String>> factory) {
        if (factories.putIfAbsent(type, descriptor -> factory.apply(type.cast(descriptor))) != null) throw new IllegalArgumentException("Duplicate tab restorer");
    }
    public CompletableFuture<String> restore(TabDescriptor descriptor) {
        var factory = factories.get(descriptor.getClass());
        if (factory == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown tab descriptor: " + descriptor.getClass().getSimpleName()));
        return factory.apply(descriptor);
    }
}
