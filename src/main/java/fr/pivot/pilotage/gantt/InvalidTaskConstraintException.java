package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.ConstraintType;

/**
 * Thrown when a client submits a scheduling constraint that is well-formed but invalid (US22.4.4
 * error AC): a type other than {@code ASAP}/{@code ALAP} without the {@code constraintDate} it
 * requires. Refused with {@code 422 Unprocessable Entity} <em>before</em> any persistence, mirroring
 * {@link InvalidTaskEffortException} — the previous constraint (if any) is left untouched.
 */
public class InvalidTaskConstraintException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for a rejected constraint. */
    public static final String CODE = "INVALID_TASK_CONSTRAINT";

    /**
     * Builds the exception with the given caller-facing message.
     *
     * @param message the explicit reason the value is invalid
     */
    public InvalidTaskConstraintException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a date-bearing constraint type submitted without its required date.
     *
     * @param type the offending constraint type
     * @return the exception
     */
    public static InvalidTaskConstraintException missingConstraintDate(final ConstraintType type) {
        return new InvalidTaskConstraintException("constraint_date is required for constraint type "
                + type + " (only ASAP/ALAP carry no date)");
    }
}
