package dev.editor.git.infrastructure;
import java.time.Duration;
public record GitConfiguration(String executable, Duration timeout, int outputLimit, int textLimit, int cacheBytes) {
    public GitConfiguration { if (executable.isBlank() || timeout.isNegative() || timeout.isZero() || outputLimit < 1 || textLimit < 1 || cacheBytes < 1) throw new IllegalArgumentException("Invalid Git configuration"); }
    public static GitConfiguration defaults() { return new GitConfiguration("git", Duration.ofSeconds(20), 32 * 1024 * 1024, 20 * 1024 * 1024, 32 * 1024 * 1024); }
}
