package dev.rilvune.document.domain;

/** Buffer acceptance is independent of later disk/recovery and view delivery. */
public record DocumentChange(Effect effect, Rejection rejection, DocumentSnapshot snapshot,
                             Throwable deliveryFailure, Throwable schedulingFailure) {
    public enum Effect { APPLIED, UNCHANGED, REJECTED }
    public enum Rejection { NONE, STALE_DOCUMENT, CLOSED, PAUSED, NEEDS_RESOLUTION, NEEDS_ATTENTION }
    public boolean accepted() { return effect != Effect.REJECTED; }
}
