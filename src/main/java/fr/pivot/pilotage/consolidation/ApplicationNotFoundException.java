package fr.pivot.pilotage.consolidation;

/**
 * Thrown when {@link ApplicationConsolidationService#consolidate(long, long)} (or the
 * {@code project → application} resolution) is asked for an {@code applicationId} that does not
 * exist <em>for the requesting tenant</em> (EN18.9 error/security cases).
 *
 * <p>An application owned by a different tenant is treated as absent — the service never confirms
 * the existence of a cross-tenant resource. This maps to an HTTP 404 (resource not found) at the
 * future controller layer, the same non-disclosure posture used across
 * {@code pivot-pilotage-core} (CLAUDE.md §Isolation tenant). The controller layer is deferred until
 * {@code pivot-core-starter} (TenantContext) is published (TODO-SETUP §5).
 */
public class ApplicationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a missing (or cross-tenant, hence invisible) application.
     *
     * @param applicationId the {@code pilotage.application.id} that was not found for the tenant
     * @param tenantId      the requesting tenant's {@code public.tenants.id}
     */
    public ApplicationNotFoundException(final long applicationId, final long tenantId) {
        super("No application " + applicationId + " visible to tenant " + tenantId);
    }
}
