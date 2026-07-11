package fr.pivot.pilotage.consolidation;

/**
 * Thrown when {@link ProjectApplicationResolver#resolveApplicationId(long, long)} is asked to
 * resolve the parent application of a {@code projectId} that does not exist <em>for the requesting
 * tenant</em> (EN18.9). A project owned by another tenant is treated as absent — non-disclosure
 * posture, maps to HTTP 404 at the future controller layer (CLAUDE.md §Isolation tenant).
 */
public class ProjectNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a missing (or cross-tenant, hence invisible) project.
     *
     * @param projectId the {@code pilotage.project.id} that was not found for the tenant
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     */
    public ProjectNotFoundException(final long projectId, final long tenantId) {
        super("No project " + projectId + " visible to tenant " + tenantId);
    }
}
