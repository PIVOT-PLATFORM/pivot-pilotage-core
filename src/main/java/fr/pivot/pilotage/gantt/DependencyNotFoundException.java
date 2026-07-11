package fr.pivot.pilotage.gantt;

/**
 * Thrown when a dependency endpoint (US22.4.3) is asked to operate on a dependency that does not
 * resolve within the requesting {@code (tenantId, teamId)} boundary — or whose endpoint task does
 * not belong to the browsed project.
 *
 * <p>Deliberately a <strong>single</strong> exception for every isolation failure — unknown
 * tenant/team, cross-tenant dependency, unknown dependency, or an endpoint task outside the project.
 * All collapse to the same bodyless {@code 404} (CLAUDE.md §Isolation tenant, non-disclosure).
 * Mirrors {@link WbsTaskNotFoundException}.
 */
public class DependencyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private DependencyNotFoundException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a dependency not visible under the given tenant/team.
     *
     * @param dependencyId the dependency id that was not found
     * @param tenantId     the requesting tenant's {@code public.tenants.id}
     * @param teamId       the requesting team's {@code public.teams.id}
     */
    public DependencyNotFoundException(final long dependencyId, final long tenantId, final long teamId) {
        super("No dependency " + dependencyId + " visible to tenant " + tenantId + "/team " + teamId);
    }

    /**
     * Builds the exception for a dependency whose endpoint task is not visible on the browsed
     * project (same non-disclosure posture).
     *
     * @param taskId    the endpoint task id that did not resolve
     * @param projectId the project the task was expected to belong to
     * @return the exception carrying a non-disclosing message
     */
    public static DependencyNotFoundException endpointTask(final long taskId, final long projectId) {
        return new DependencyNotFoundException("No task " + taskId + " visible on project " + projectId);
    }
}
