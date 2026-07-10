package fr.pivot.pilotage.schedule;

/**
 * Scheduling constraint type applied to a task (EN22.1a, frozen contract §a).
 *
 * <p>{@link #ASAP}/{@link #ALAP} carry no date; the other types require a {@code constraint_date}
 * — a rule enforced at the service/engine layer (EN22.1b), not by the schema.
 */
public enum ConstraintType {

    /** As soon as possible. */
    ASAP,

    /** As late as possible. */
    ALAP,

    /** Must start on. */
    MSO,

    /** Must finish on. */
    MFO,

    /** Start no earlier than. */
    SNET,

    /** Start no later than. */
    SNLT,

    /** Finish no earlier than. */
    FNET,

    /** Finish no later than. */
    FNLT
}
