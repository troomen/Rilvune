package dev.rilvune.platform.text.domain.syntax;

/** Expected failures carry only bounded, source-free user messages. */
public final class SyntaxFailure extends RuntimeException {
    public enum Reason { CANCELLED, LIMIT, INVALID_OUTPUT }
    private final Reason reason;
    public SyntaxFailure(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
    public static SyntaxFailure limit(String message) { return new SyntaxFailure(Reason.LIMIT, message); }
    public static SyntaxFailure invalid() { return new SyntaxFailure(Reason.INVALID_OUTPUT, "Invalid syntax provider output"); }
}
