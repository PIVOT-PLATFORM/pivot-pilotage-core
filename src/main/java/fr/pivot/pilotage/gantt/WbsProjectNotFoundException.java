package fr.pivot.pilotage.gantt;

/**
 * Thrown when a WBS Gantt endpoint (US22.4.1a/b/c) is asked to operate on a
 * {@code (tenantId, teamId, projectId)} triple that does not resolve to a visible project.
 *
 * <p>Deliberately a <strong>single</strong> exception for every isolation failure — unknown
 * tenant, unknown team, cross-team project, unknown project — all collapse to the same bodyless
 * {@code 404} (CLAUDE.md §Isolation tenant, non-disclosure). Mirrors
 * {@code fr.pivot.pilotage.roadmap.ProjectNotFoundException}: the three path segments jointly form
 * one compound isolation lookup ({@code ProjectRepository.findByIdAndTenantIdAndTeamId}), so a
 * single exception type is both simpler and a strictly stronger non-disclosure posture.
 */
public class WbsProjectNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a project not visible under the given tenant/team.
     *
     * @param projectId the {@code pilotage.project.id} that was not found
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     */
    public WbsProjectNotFoundException(final long projectId, final long tenantId, final long teamId) {
        super("No project " + projectId + " visible to tenant " + tenantId + "/team " + teamId);
    }
}
