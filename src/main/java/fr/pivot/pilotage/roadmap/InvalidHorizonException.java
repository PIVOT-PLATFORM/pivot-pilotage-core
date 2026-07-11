package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a Now/Next/Later bucket move (US22.3.3, {@code PATCH .../initiatives/{id}/horizon})
 * supplies no target horizon (an unknown enum token is rejected by Jackson before this point; this
 * guards the explicit {@code null} case). Mapped to 400 with an {@link ApiError} body by
 * {@link RoadmapExceptionHandler}, giving a caller-facing message a bodyless response could not.
 */
public class InvalidHorizonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable code carried by the {@link ApiError} body. */
    static final String CODE = "HORIZON_REQUIRED";

    /**
     * Builds the exception for a request that supplied no target horizon.
     *
     * @param initiativeId the initiative whose bucket was being changed
     */
    public InvalidHorizonException(final long initiativeId) {
        super("A target horizon (NOW/NEXT/LATER) is required to move initiative " + initiativeId);
    }
}
