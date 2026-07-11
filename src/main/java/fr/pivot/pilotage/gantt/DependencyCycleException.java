package fr.pivot.pilotage.gantt;

/**
 * Thrown when creating or updating a dependency would introduce a <strong>cycle</strong> in the
 * project's temporal graph (US22.4.3). The rejection originates from the scheduling engine
 * (EN22.1b, {@link fr.pivot.pilotage.schedule.engine.ScheduleErrorCode#SCHEDULE_CYCLE}) which runs
 * inside the same transaction as the tentative persist, so <strong>no partial state</strong> is ever
 * committed — the whole change is rolled back atomically (see {@link DependencyService}).
 *
 * <p>Mapped to {@code 409 Conflict} by {@link WbsExceptionHandler} carrying the engine's own error
 * code {@value #CODE} — deliberately distinct from the WBS <em>hierarchy</em> cycle
 * ({@code WBS_HIERARCHY_CYCLE}, also 409): decision D4 keeps the temporal-graph cycle and the
 * parent/child hierarchy cycle as two separate semantics.
 */
public class DependencyCycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code, mirroring the engine's {@code SCHEDULE_CYCLE}. */
    public static final String CODE = "SCHEDULE_CYCLE";

    /**
     * Builds the exception carrying the engine's cycle diagnostic.
     *
     * @param message the human-readable reason from the engine
     */
    public DependencyCycleException(final String message) {
        super(message);
    }
}
