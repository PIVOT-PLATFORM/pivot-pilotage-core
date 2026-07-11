package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a roadmap-rapide milestone operation (US22.3.4, {@code PATCH .../milestones/{id}})
 * targets a task id that either does not exist, does not belong to the given project/tenant/team,
 * or exists but is not a strategic milestone at all ({@code node_kind != MILESTONE}).
 *
 * <p>Mapped to 404 by {@link RoadmapExceptionHandler} — same non-disclosure posture as
 * {@link ProjectNotFoundException} and {@link InitiativeNotFoundException} (CLAUDE.md §Isolation
 * tenant).
 */
public class MilestoneNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a milestone not visible/resolvable under the given project.
     *
     * @param milestoneId the task id that was not found as a strategic milestone
     * @param projectId   the project the milestone was expected to belong to
     */
    public MilestoneNotFoundException(final long milestoneId, final long projectId) {
        super("No milestone " + milestoneId + " on project " + projectId);
    }
}
