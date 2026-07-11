package fr.pivot.pilotage.schedule.engine;

/**
 * Effective scheduling mode of a task inside the engine (EN22.1b) — the project default already
 * resolved into a concrete value.
 *
 * <p>This mirrors {@code fr.pivot.pilotage.schedule.SchedulingMode} but lives in the pure engine
 * core so that the core carries no dependency on the JPA/enum layer of EN22.1a. The service maps
 * the persisted (nullable, project-inheriting) mode onto this concrete value before building the
 * {@link ScheduleInput}.
 */
public enum TaskMode {

    /** Dates recomputed by the engine from dependencies, constraints and calendars. */
    AUTO,

    /** Dates pinned by the caller; the engine reports variance instead of moving them. */
    MANUAL
}
