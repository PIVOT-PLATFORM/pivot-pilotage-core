package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.DependencyLinkType;

/**
 * Thrown when a dependency creation would duplicate an already-persisted link (US22.4.3 error AC):
 * the same {@code (predecessor, successor, link_type)} triple already exists — which the schema also
 * forbids via {@code UNIQUE(predecessor, successor, link_type)}, but is rejected here first with an
 * explicit caller-facing message rather than surfacing a raw constraint violation.
 *
 * <p>Mapped to {@code 409 Conflict} by {@link WbsExceptionHandler} — a conflict with existing state,
 * distinct from the {@code 422} semantic-payload error ({@link InvalidDependencyException}) and from
 * the {@code 409} cycle rejection ({@link DependencyCycleException}, code {@code SCHEDULE_CYCLE}).
 */
public class DuplicateDependencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for the error body. */
    public static final String CODE = "DUPLICATE_DEPENDENCY";

    /**
     * Builds the exception for a duplicate link.
     *
     * @param predecessorTaskId the predecessor task id
     * @param successorTaskId   the successor task id
     * @param linkType          the link type that already exists
     */
    public DuplicateDependencyException(final long predecessorTaskId, final long successorTaskId,
            final DependencyLinkType linkType) {
        super("A " + linkType + " dependency from task " + predecessorTaskId + " to task "
                + successorTaskId + " already exists");
    }
}
