package fr.pivot.pilotage.gantt;

/**
 * Thrown when a periodic task creation request (US22.4.6 error AC) is missing the mandatory
 * recurrence definition, or asks for more occurrences than {@link RecurringTaskService} generates in
 * one call.
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} by {@link WbsExceptionHandler}. Distinct from a
 * merely malformed JSON body (which stays a {@code 400}, see {@link CreateRecurringTaskRequest}'s
 * class note on why {@code frequency}/{@code occurrenceCount} are deliberately not bean-validated).
 */
public class InvalidRecurrenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for the error body. */
    public static final String CODE = "INVALID_RECURRENCE";

    /**
     * Builds the exception with an explicit caller-facing message.
     *
     * @param message the human-readable reason the recurrence was rejected
     */
    public InvalidRecurrenceException(final String message) {
        super(message);
    }

    /**
     * Builds the «&nbsp;missing frequency or occurrence count&nbsp;» error (US22.4.6 error AC): a
     * recurring task requires both a {@link RecurrenceFrequency} and a strictly positive occurrence
     * count.
     *
     * @return the exception carrying the explicit message
     */
    public static InvalidRecurrenceException missingFrequencyOrOccurrenceCount() {
        return new InvalidRecurrenceException(
                "A recurring task requires a frequency and a positive occurrence count "
                        + "(frequency and occurrenceCount must both be supplied, occurrenceCount > 0)");
    }

    /**
     * Builds the «&nbsp;too many occurrences&nbsp;» error: the requested occurrence count exceeds the
     * generation cap (backlog note: "prévoir une limite raisonnable... pour éviter une explosion du
     * graphe", EN22.2 perf).
     *
     * @param requested the requested occurrence count
     * @param max       the generation cap
     * @return the exception carrying the explicit message
     */
    public static InvalidRecurrenceException tooManyOccurrences(final int requested, final int max) {
        return new InvalidRecurrenceException(
                "occurrenceCount=" + requested + " exceeds the maximum of " + max
                        + " occurrences generated in a single call (EN22.2 perf guard against WBS graph explosion)");
    }
}
