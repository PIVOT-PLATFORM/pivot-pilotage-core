package fr.pivot.pilotage.roadmap;

/**
 * Thrown when a roadmap-rapide endpoint (US22.3.1) is asked to operate on a
 * {@code (tenantId, teamId, projectId)} triple that does not resolve to a visible project.
 *
 * <p>Deliberately a <strong>single</strong> exception for every way this can fail — unknown
 * tenant, unknown team, a team belonging to a different tenant, an unknown project, or a project
 * belonging to a different tenant/team — all collapse to the same 404 (CLAUDE.md §Isolation
 * tenant, non-disclosure posture). Unlike {@code OrganizationProfileOverrideService}
 * (EN18.10 écart #3), which validates a path {@code tenantId} and a body {@code teamId} as two
 * independently-meaningful values (so needed two distinct exceptions), here {@code tenantId},
 * {@code teamId} and {@code projectId} are three path segments jointly forming <em>one</em>
 * compound isolation lookup ({@code ProjectRepository.findByIdAndTenantIdAndTeamId}) — a single
 * exception type is not just simpler, it is a strictly stronger non-disclosure posture (it does
 * not even let a caller distinguish "unknown tenant" from "unknown team" from "cross-team
 * project").
 */
public class ProjectNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a project not visible under the given tenant/team.
     *
     * @param projectId the {@code pilotage.project.id} that was not found
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     */
    public ProjectNotFoundException(final long projectId, final long tenantId, final long teamId) {
        super("No project " + projectId + " visible to tenant " + tenantId + "/team " + teamId);
    }
}
