package dev.editor.diff.domain;

/** Half-open UTF-16 offsets into an original snapshot, never into normalized display text. */
public record TextSpan(int startOffset, int endOffset) {
    public TextSpan {
        if (startOffset < 0 || endOffset < startOffset) throw new IllegalArgumentException("Invalid text span");
    }
    public boolean empty() { return startOffset == endOffset; }
    public int length() { return endOffset - startOffset; }
    public boolean contains(TextSpan other) { return other.startOffset >= startOffset && other.endOffset <= endOffset; }
}
