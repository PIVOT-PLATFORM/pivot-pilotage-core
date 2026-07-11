package fr.pivot.pilotage.gantt;

/**
 * Thrown when a WBS Gantt mutation (US22.4.1b/c) targets a task id that does not exist or does not
 * belong to the given project/tenant/team. Mapped to a bodyless {@code 404} by
 * {@link WbsExceptionHandler} — same non-disclosure posture as {@link WbsProjectNotFoundException}
 * (CLAUDE.md §Isolation tenant).
 */
public class WbsTaskNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a task not visible/resolvable under the given project.
     *
     * @param taskId    the task id that was not found
     * @param projectId the project the task was expected to belong to
     */
    public WbsTaskNotFoundException(final long taskId, final long projectId) {
        super("No WBS task " + taskId + " on project " + projectId);
    }
}
