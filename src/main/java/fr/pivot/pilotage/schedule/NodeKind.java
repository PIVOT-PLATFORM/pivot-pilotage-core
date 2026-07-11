package fr.pivot.pilotage.schedule;

/**
 * Kind of graph node carried by a {@code task} (EN22.1a, frozen contract §a).
 */
public enum NodeKind {

    /** Summary (recap) task aggregating its children — aggregates computed by the engine. */
    SUMMARY,

    /** Leaf task with its own duration. */
    LEAF,

    /** Milestone: a zero-duration marker; when shared, projected into the roadmap view. */
    MILESTONE,

    /** Recurring task driven by a {@code recurrence_rule} (iCalendar). */
    RECURRING
}
