package fr.pivot.pilotage.gantt;

/**
 * Thrown when a dependency creation/update is semantically invalid (US22.4.3 error AC) — currently
 * a <strong>self-dependency</strong> (a task linked to itself), which the schema also forbids via
 * {@code CHECK predecessor <> successor} but is rejected here first with an explicit caller-facing
 * message rather than surfacing a raw constraint violation.
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} by {@link WbsExceptionHandler}. Distinct from a
 * merely malformed JSON body (which stays a {@code 400}) and from the duplicate-link conflict
 * ({@code 409}, {@link DuplicateDependencyException}).
 */
public class InvalidDependencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for the error body. */
    public static final String CODE = "INVALID_DEPENDENCY";

    /**
     * Builds the exception with an explicit caller-facing message.
     *
     * @param message the human-readable reason the dependency was rejected
     */
    public InvalidDependencyException(final String message) {
        super(message);
    }

    /**
     * Builds the «&nbsp;self-dependency&nbsp;» error (a task cannot depend on itself).
     *
     * @param taskId the task that was linked to itself
     * @return the exception carrying the explicit message
     */
    public static InvalidDependencyException selfDependency(final long taskId) {
        return new InvalidDependencyException(
                "A task cannot depend on itself (task " + taskId + " as both predecessor and successor)");
    }
}
