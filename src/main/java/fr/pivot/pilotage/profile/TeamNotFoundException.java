package fr.pivot.pilotage.profile;

/**
 * Thrown when an organization-profile override write is asked to attribute the override to a
 * {@code teamId} that does not exist in {@code public.teams}, <strong>or</strong> exists but
 * belongs to a different tenant than the one being overridden (EN18.10 écart #1/#3).
 *
 * <p>Both cases are reported identically — the service never confirms the existence of a
 * cross-tenant team, the same non-disclosure posture used across {@code pivot-pilotage-core}
 * (CLAUDE.md §Isolation tenant). Maps to HTTP 404 at {@link OrganizationProfileController}.
 */
public class TeamNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a missing (or cross-tenant, hence invisible) team.
     *
     * @param teamId   the {@code public.teams.id} that was not found for the tenant
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     */
    public TeamNotFoundException(final long teamId, final long tenantId) {
        super("No team " + teamId + " visible to tenant " + tenantId);
    }
}
