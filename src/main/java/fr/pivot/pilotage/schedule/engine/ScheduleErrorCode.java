package fr.pivot.pilotage.schedule.engine;

/**
 * Typed error codes raised by the scheduling engine (EN22.1b, frozen contract §b).
 */
public enum ScheduleErrorCode {

    /** The dependency graph (or a delta) introduces a cycle — the whole request is rejected. */
    SCHEDULE_CYCLE,

    /** The input mixes more than one tenant — the engine never schedules a multi-tenant graph. */
    TENANT_VIOLATION,

    /** A task references a calendar absent from the input and no project default resolves. */
    UNKNOWN_CALENDAR,

    /** A delta targets a stale base version — the caller must rebase. */
    STALE_BASE_VERSION,

    /** A delta references an entity absent from the previous state. */
    UNKNOWN_ENTITY
}
