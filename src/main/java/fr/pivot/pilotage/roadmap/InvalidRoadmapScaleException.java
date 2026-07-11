package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a roadmap scale update (US22.3.2, {@code PUT .../roadmap/scale}) supplies no scale at
 * all (or an unparseable token — Jackson rejects unknown enum values before this point, this guards
 * the explicit {@code null} case). Mapped to 400 with an {@link ApiError} body by
 * {@link RoadmapExceptionHandler}, giving a caller-facing message the way a bodyless response could
 * not.
 */
public class InvalidRoadmapScaleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable code carried by the {@link ApiError} body. */
    static final String CODE = "SCALE_REQUIRED";

    /**
     * Builds the exception for a request that supplied no scale.
     *
     * @param projectId the project whose roadmap scale was being set
     */
    public InvalidRoadmapScaleException(final long projectId) {
        super("A scale is required to set the roadmap scale on project " + projectId);
    }
}
