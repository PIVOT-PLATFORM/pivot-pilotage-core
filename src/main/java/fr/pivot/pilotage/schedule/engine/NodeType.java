package fr.pivot.pilotage.schedule.engine;

/**
 * Kind of graph node consumed by the engine (EN22.1b) — a pure-core mirror of
 * {@code fr.pivot.pilotage.schedule.NodeKind}.
 */
public enum NodeType {

    /** Summary (recap) task aggregating its children — start=min, finish=max. */
    SUMMARY,

    /** Leaf task with its own duration. */
    LEAF,

    /** Milestone: a zero-duration marker. */
    MILESTONE,

    /** Recurring task; treated as a leaf for CPM purposes. */
    RECURRING
}
