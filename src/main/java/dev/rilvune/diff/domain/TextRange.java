package dev.rilvune.diff.domain;

/** Half-open ranges in logical lines and original UTF-16 text. */
public record TextRange(int firstLine, int endLine, int startOffset, int endOffset) {
    public TextRange {
        if (firstLine < 0 || endLine < firstLine || startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("Invalid text range");
        }
    }
    public boolean empty() { return startOffset == endOffset; }
    public int length() { return endOffset - startOffset; }
    public TextSpan span() { return new TextSpan(startOffset, endOffset); }
    public boolean contains(TextRange other) {
        return other.firstLine >= firstLine && other.endLine <= endLine && span().contains(other.span());
    }
}
