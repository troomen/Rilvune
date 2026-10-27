package dev.rilvune.document.domain;

public enum LineEnding {
    LF("\n"), CRLF("\r\n"), CR("\r"), MIXED("\n");

    private final String separator;

    LineEnding(String separator) { this.separator = separator; }

    public String separator() { return separator; }
}
