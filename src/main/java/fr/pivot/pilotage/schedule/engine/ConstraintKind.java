package fr.pivot.pilotage.schedule.engine;

/**
 * Scheduling constraint kind consumed by the engine (EN22.1b) — a pure-core mirror of
 * {@code fr.pivot.pilotage.schedule.ConstraintType}.
 */
public enum ConstraintKind {

    /** As soon as possible (no date). */
    ASAP,

    /** As late as possible (no date). */
    ALAP,

    /** Must start on the constraint date. */
    MSO,

    /** Must finish on the constraint date. */
    MFO,

    /** Start no earlier than the constraint date (forward floor). */
    SNET,

    /** Start no later than the constraint date (backward ceiling). */
    SNLT,

    /** Finish no earlier than the constraint date (forward floor). */
    FNET,

    /** Finish no later than the constraint date (backward ceiling). */
    FNLT
}
