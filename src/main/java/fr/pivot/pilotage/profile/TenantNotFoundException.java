package fr.pivot.pilotage.profile;

/**
 * Thrown when {@link OrganizationProfileResolver#resolveProfile(long)} is asked to resolve a
 * profile for a {@code tenantId} that does not exist in {@code public.tenants} (EN18.10, frozen
 * contract §c error case).
 *
 * <p>The resolver <strong>never fabricates a phantom profile</strong> for an unknown tenant: it
 * fails fast. This maps to an HTTP 404 (resource not found) at the future controller layer — the
 * same non-disclosure posture used across {@code pivot-pilotage-core} for cross-tenant access
 * (CLAUDE.md §Isolation tenant). The controller layer is deferred until {@code pivot-core-starter}
 * (TenantContext) is published.
 */
public class TenantNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a missing tenant.
     *
     * @param tenantId the {@code public.tenants.id} that was not found
     */
    public TenantNotFoundException(final long tenantId) {
        super("No tenant found for id " + tenantId);
    }
}
