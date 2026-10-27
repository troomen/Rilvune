package dev.rilvune.platform.text.domain.syntax;

import java.time.Duration;
import java.util.Objects;

public record SyntaxLimits(int characters, int lines, int lineCharacters, int spans,
                           int workers, int entries, int handles, long payloadBytes,
                           Duration debounce, Duration workTime) {
    public SyntaxLimits {
        Objects.requireNonNull(debounce); Objects.requireNonNull(workTime);
        if (characters < 1 || lines < 1 || lineCharacters < 1 || spans < 1 || spans > Integer.MAX_VALUE / 24
                || workers < 1 || entries < 1 || handles < 1 || payloadBytes < 1
                || debounce.isNegative() || debounce.toMillis() > Integer.MAX_VALUE
                || workTime.isNegative() || workTime.isZero()) throw new IllegalArgumentException("Invalid syntax limits");
    }
    public static SyntaxLimits interactive() {
        return new SyntaxLimits(2_097_152, 20_000, 16_384, 100_000, 2, 256, 512,
                32L * 1024 * 1024, Duration.ofMillis(100), Duration.ofMillis(250));
    }
    /** Includes String capture, temporary character copies and both builder/final compact span arrays. */
    public long jobBytes(int length) { return 1024L + 8L * length + 24L * spans; }
}
