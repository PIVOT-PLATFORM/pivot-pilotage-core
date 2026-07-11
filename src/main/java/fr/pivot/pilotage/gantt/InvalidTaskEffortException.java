package fr.pivot.pilotage.gantt;

/**
 * Thrown when a client submits an invalid duration or effort/units on a task (US22.4.2 error AC):
 * <ul>
 *   <li>a negative duration in worked minutes;</li>
 *   <li>a zero (or {@code null}) duration on a non-milestone task — a real task must have a
 *       positive duration, only a {@code MILESTONE} may be zero-duration;</li>
 *   <li>a non-positive resource units percentage (the {@code effort} lever), which would make the
 *       work = duration × units relation collapse to zero or go negative.</li>
 * </ul>
 *
 * <p>A non-numeric value never reaches this exception: it is rejected earlier by Jackson binding
 * (mapped to {@code 400} by {@link WbsExceptionHandler}) unless it targets a typed derived field.
 * Every case mapped here is a well-formed-but-invalid value, refused with {@code 422 Unprocessable
 * Entity}; the task keeps its previous values because the guard runs before any persistence.
 */
public class InvalidTaskEffortException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for a rejected duration/effort value. */
    public static final String CODE = "INVALID_TASK_EFFORT";

    /**
     * Builds the exception with the given caller-facing message.
     *
     * @param message the explicit reason the value is invalid
     */
    public InvalidTaskEffortException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a negative duration.
     *
     * @param durationMinutes the rejected duration
     * @return the exception
     */
    public static InvalidTaskEffortException negativeDuration(final long durationMinutes) {
        return new InvalidTaskEffortException("duration_minutes must be >= 0, was " + durationMinutes);
    }

    /**
     * Builds the exception for a zero/absent duration on a non-milestone task.
     *
     * @param taskId the offending task id
     * @return the exception
     */
    public static InvalidTaskEffortException zeroDurationNonMilestone(final long taskId) {
        return new InvalidTaskEffortException("task " + taskId + " is not a milestone; its "
                + "duration_minutes must be > 0 (only a milestone may be zero-duration)");
    }

    /**
     * Builds the exception for a non-positive units percentage.
     *
     * @return the exception
     */
    public static InvalidTaskEffortException nonPositiveUnits() {
        return new InvalidTaskEffortException("units_percent must be > 0 so that "
                + "work = duration x units stays positive");
    }
}
