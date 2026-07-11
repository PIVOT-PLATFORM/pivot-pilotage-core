package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a roadmap-rapide write (US22.3.1 — create or move/resize an initiative) has no usable
 * lane: either no {@code laneId} at all ({@link #missing(long)} — AC "Error: given an initiative
 * without a target lane, when I try to save it, then the action is rejected and a message indicates
 * a lane is required"), or a {@code laneId} that does not exist, or exists but belongs to a
 * different project/tenant/team than the one being edited ({@link #invalid(long, long)}).
 *
 * <p>Mapped to <strong>400</strong> (not 404) by {@link RoadmapExceptionHandler} — deliberately
 * different from the path-level {@link ProjectNotFoundException}/{@link InitiativeNotFoundException}
 * non-disclosure posture: {@code laneId} is a body-supplied reference to a sub-resource already
 * scoped inside an authenticated tenant/team/project context (never a top-level resource whose
 * existence must be hidden), and the AC explicitly asks for this exact contract: "Erreur 400 si
 * lane absente/invalide". The response body ({@link ApiError}) carries a caller-facing message so
 * the frontend can point the user at "you must pick a valid lane" — it does not reveal whether
 * lanes with that id exist for other tenants.
 *
 * <p><strong>Not left to Spring's default bean-validation error body.</strong> A {@code @NotNull}
 * on {@code CreateInitiativeRequest.laneId()} would give a 400 too, but Spring Boot's default error
 * body omits the field-level message unless {@code server.error.include-message}/
 * {@code include-binding-errors} are explicitly set to {@code always} (neither is configured in
 * this module) — the AC's "un message indique qu'une lane est requise" would not reliably hold.
 * {@link #missing(long)} is therefore raised explicitly in {@code RoadmapService}, giving full
 * control over the message via {@link ApiError}.
 */
public class LaneNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable code for the "no lane at all" case. */
    static final String CODE_REQUIRED = "LANE_REQUIRED";

    /** Machine-readable code for the "unknown/foreign lane" case. */
    static final String CODE_NOT_FOUND = "LANE_NOT_FOUND";

    private final String code;

    private LaneNotFoundException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Builds the exception for a request that supplied no {@code laneId} at all.
     *
     * @param projectId the project the initiative was being created on
     * @return the exception, carrying {@link #CODE_REQUIRED}
     */
    public static LaneNotFoundException missing(final long projectId) {
        return new LaneNotFoundException(CODE_REQUIRED,
                "A lane is required to create an initiative on project " + projectId);
    }

    /**
     * Builds the exception for a {@code laneId} not visible under the given project.
     *
     * @param laneId    the {@code pilotage.lane.id} that was not found
     * @param projectId the project the lane was expected to belong to
     * @return the exception, carrying {@link #CODE_NOT_FOUND}
     */
    public static LaneNotFoundException invalid(final long laneId, final long projectId) {
        return new LaneNotFoundException(CODE_NOT_FOUND, "No lane " + laneId + " on project " + projectId);
    }

    /**
     * Returns the machine-readable error code carried by this exception.
     *
     * @return {@link #CODE_REQUIRED} or {@link #CODE_NOT_FOUND}
     */
    String code() {
        return code;
    }
}
