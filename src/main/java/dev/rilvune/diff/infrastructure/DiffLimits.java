package dev.rilvune.diff.infrastructure;

import java.time.Duration;

public record DiffLimits(int maximumCharacters, int maximumLines, long maximumWork, Duration timeout) {
    public DiffLimits {
        if (maximumCharacters < 1 || maximumLines < 1 || maximumWork < 1 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Diff limits must be positive");
        }
    }
    public static DiffLimits interactive() {
        return new DiffLimits(20 * 1024 * 1024, 100_000, 2_000_000, Duration.ofSeconds(2));
    }
}
