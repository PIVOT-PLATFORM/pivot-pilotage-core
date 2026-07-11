package fr.pivot.pilotage.schedule.engine;

/**
 * A typed, non-fatal warning emitted by the engine (EN22.1b) when a constraint would fight a hard
 * dependency, a deadline is missed, or float goes negative. A dependency is <strong>never</strong>
 * broken — the dependency is honoured and this warning records the conflict.
 *
 * @param type   the warning kind
 * @param taskId the task the warning is about
 * @param detail a human-readable diagnostic
 */
public record SchedulingWarning(WarningType type, long taskId, String detail) {

    /** Warning kinds. */
    public enum WarningType {

        /** A hard dependency overrode a task constraint; the constraint could not be honoured. */
        CONSTRAINT_CONFLICT,

        /** A task's early finish is past its soft deadline. */
        DEADLINE_MISSED,

        /** A task's total float is negative (schedule is over-constrained). */
        NEGATIVE_FLOAT,

        /** A delta was rejected as a whole (e.g. it introduced a cycle). */
        REJECTED
    }
}
