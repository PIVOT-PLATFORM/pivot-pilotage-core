package fr.pivot.pilotage.gantt;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Thrown when a client submits an invalid task-progress value (US22.4.8 error AC):
 * <ul>
 *   <li>a percent complete (temporal or physical) outside {@code [0, 100]};</li>
 *   <li>an actual finish date strictly before the actual start date.</li>
 * </ul>
 *
 * <p>A non-numeric/non-date value never reaches this exception: it is rejected earlier by Jackson
 * binding (mapped to {@code 400} by {@link WbsExceptionHandler}). Every case mapped here is a
 * well-formed-but-invalid value, refused with {@code 422 Unprocessable Entity}; the task's progress
 * keeps its previous values because the guard runs before any persistence.
 */
public class InvalidTaskProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for a rejected progress value. */
    public static final String CODE = "INVALID_TASK_PROGRESS";

    /**
     * Builds the exception with the given caller-facing message.
     *
     * @param message the explicit reason the value is invalid
     */
    public InvalidTaskProgressException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a percent complete outside {@code [0, 100]}.
     *
     * @param percent the rejected percent value
     * @return the exception
     */
    public static InvalidTaskProgressException percentOutOfRange(final BigDecimal percent) {
        return new InvalidTaskProgressException("percentComplete must be within [0, 100], was " + percent);
    }

    /**
     * Builds the exception for an actual finish date preceding the actual start date.
     *
     * @param actualStart  the rejected pair's actual start
     * @param actualFinish the rejected pair's actual finish
     * @return the exception
     */
    public static InvalidTaskProgressException actualFinishBeforeActualStart(final Instant actualStart,
            final Instant actualFinish) {
        return new InvalidTaskProgressException("actualFinish (" + actualFinish
                + ") must not be before actualStart (" + actualStart + ")");
    }
}
