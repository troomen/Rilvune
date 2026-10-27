package dev.rilvune.document.domain;

import java.util.Objects;

/** Text presented to the UI always uses LF. Encoding is strictly UTF-8. */
public record TextFormat(boolean byteOrderMark, LineEnding lineEnding) {
    public TextFormat { Objects.requireNonNull(lineEnding, "lineEnding"); }

    public static TextFormat utf8Lf() { return new TextFormat(false, LineEnding.LF); }
}
