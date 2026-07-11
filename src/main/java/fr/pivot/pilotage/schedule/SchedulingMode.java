package fr.pivot.pilotage.schedule;

/**
 * Scheduling mode of a project or task (EN22.1a, frozen contract §a).
 *
 * <p>{@link #AUTO} means dates are recomputed by the scheduling engine (EN22.1b) on every run;
 * {@link #MANUAL} means dates are pinned and the engine only reports the variance versus the
 * theoretical automatic date. A task with a {@code null} mode inherits its project's mode.
 */
public enum SchedulingMode {

    /** Dates recomputed by the engine from dependencies, constraints and calendars. */
    AUTO,

    /** Dates pinned by the user; the engine reports variance instead of moving them. */
    MANUAL
}
