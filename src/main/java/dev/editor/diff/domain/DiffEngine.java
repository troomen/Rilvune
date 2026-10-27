package dev.editor.diff.domain;

import java.util.function.BooleanSupplier;

public interface DiffEngine {
    DiffResult compare(TextSnapshot left, TextSnapshot right, DiffOptions options, BooleanSupplier cancelled);
}
