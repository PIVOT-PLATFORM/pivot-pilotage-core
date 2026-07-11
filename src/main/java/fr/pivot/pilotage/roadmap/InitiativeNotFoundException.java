package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a roadmap-rapide move/resize (US22.3.1, {@code PATCH .../initiatives/{id}}) targets
 * a task id that either does not exist, does not belong to the given project/tenant/team, or
 * exists but is not a roadmap-rapide initiative at all (a plain Gantt task with no
 * {@code lane_id} is not a resource this endpoint exposes).
 *
 * <p>Mapped to 404 by {@link RoadmapExceptionHandler} — same non-disclosure posture as
 * {@link ProjectNotFoundException} (CLAUDE.md §Isolation tenant).
 */
public class InitiativeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for an initiative not visible/resolvable under the given project.
     *
     * @param initiativeId the task id that was not found as a roadmap-rapide initiative
     * @param projectId    the project the initiative was expected to belong to
     */
    public InitiativeNotFoundException(final long initiativeId, final long projectId) {
        super("No roadmap-rapide initiative " + initiativeId + " on project " + projectId);
    }
}
