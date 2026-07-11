package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a roadmap-rapide initiative's approximate period (US22.3.1,
 * {@code fuzzy_period_start}/{@code fuzzy_period_end}) is supplied inconsistently: only one bound
 * given (they must travel together, or neither), or the end precedes the start.
 *
 * <p>Mapped to 400 with an {@link ApiError} body by {@link RoadmapExceptionHandler}. Not itself an
 * AC of the backlog Gate 1 file, but a minimal sanity guard a PO/Architect call for this
 * under-specified US: it costs nothing and prevents a visibly nonsensical bar (end before start)
 * from ever being persisted.
 */
public class InvalidInitiativePeriodException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception with a caller-facing message.
     *
     * @param message the reason the period is rejected
     */
    public InvalidInitiativePeriodException(final String message) {
        super(message);
    }
}
