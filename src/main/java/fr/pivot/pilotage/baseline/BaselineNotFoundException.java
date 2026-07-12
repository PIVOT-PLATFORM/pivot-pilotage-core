package fr.pivot.pilotage.baseline;

/**
 * Thrown when a baseline endpoint (US22.4.9) targets a {@code baselineIndex} that does not resolve
 * to a baseline set on the given project (never yet posed, or already deleted).
 *
 * <p>Mapped to a bodyless {@code 404} by {@link BaselineExceptionHandler} — same non-disclosure
 * posture as {@link BaselineProjectNotFoundException}; a plain "not found" and a cross-tenant miss
 * are indistinguishable to the caller by design.
 */
public class BaselineNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a baseline index not resolving on the given project.
     *
     * @param projectId     the owning project id
     * @param baselineIndex the baseline slot that was not found
     */
    public BaselineNotFoundException(final long projectId, final short baselineIndex) {
        super("No baseline at index " + baselineIndex + " visible on project " + projectId);
    }
}
