package fr.pivot.pilotage.calendar;

/**
 * Thrown when a calendar or calendar-exception payload is semantically invalid (US22.4.5) — a
 * working-time range that is not whole-hour aligned or malformed, or an exception whose end date is
 * before its start date. Mapped to HTTP {@code 422 Unprocessable Entity} with an explicit message by
 * {@link CalendarExceptionHandler}. Distinct from a merely malformed JSON body (which stays a 400).
 */
public class InvalidCalendarException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for the {@link CalendarApiError} body. */
    public static final String CODE = "INVALID_CALENDAR_EXCEPTION";

    /**
     * Builds the exception with an explicit caller-facing message.
     *
     * @param message the human-readable reason the payload was rejected
     */
    public InvalidCalendarException(final String message) {
        super(message);
    }

    /**
     * Builds the «&nbsp;end before start&nbsp;» error (US22.4.5 error AC) for an exception interval.
     *
     * @param start the requested start date
     * @param end   the requested end date (earlier than {@code start})
     * @return the exception carrying the explicit message
     */
    public static InvalidCalendarException endBeforeStart(final Object start, final Object end) {
        return new InvalidCalendarException(
                "calendar exception end date " + end + " is before its start date " + start);
    }
}
