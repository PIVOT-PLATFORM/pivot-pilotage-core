package fr.pivot.pilotage.baseline;

/**
 * Thrown when a client supplies an explicit {@code baselineIndex} outside the valid MS-Project-parity
 * range (US22.4.9): a baseline slot must be {@code 0..10} ({@code Baseline}, {@code Baseline 1}..
 * {@code Baseline 10}).
 *
 * <p>A well-formed-but-invalid value, refused with {@code 422 Unprocessable Entity} by
 * {@link BaselineExceptionHandler}; distinct from {@link BaselineLimitExceededException} (a
 * {@code 409} conflict raised only when the index is <em>omitted</em> and every valid slot is
 * already used).
 */
public class InvalidBaselineIndexException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for the error body. */
    public static final String CODE = "INVALID_BASELINE_INDEX";

    private InvalidBaselineIndexException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for an out-of-range baseline index.
     *
     * @param baselineIndex the rejected index
     * @return the exception
     */
    public static InvalidBaselineIndexException outOfRange(final short baselineIndex) {
        return new InvalidBaselineIndexException("baselineIndex must be between 0 and 10 "
                + "(MS Project parity: Baseline + Baseline 1..10), was " + baselineIndex);
    }
}
