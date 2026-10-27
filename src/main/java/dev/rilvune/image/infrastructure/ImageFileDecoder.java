package dev.rilvune.image.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Worker-side decode contract; its successful result transfers explicit pixel ownership. */
@FunctionalInterface
public interface ImageFileDecoder {
    ImageFileLoader.LoadedImage load(Path path, BooleanSupplier canceled) throws IOException;
}
