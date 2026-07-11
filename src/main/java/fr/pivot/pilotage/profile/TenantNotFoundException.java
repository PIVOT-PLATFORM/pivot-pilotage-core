package fr.pivot.pilotage.profile;

/**
 * Thrown when {@link OrganizationProfileResolver#resolveProfile(long)} — or the override write
 * path, {@link OrganizationProfileOverrideService#upsertOverride}) — is asked to resolve/write a
 * profile for a {@code tenantId} that does not exist in {@code public.tenants} (EN18.10, frozen
 * contract §c error case).
 *
 * <p>Never a fabricated phantom profile for an unknown tenant: this fails fast. On the write path
 * (EN18.10 écart #3), {@link OrganizationProfileExceptionHandler} maps this to HTTP 404 — the same
 * non-disclosure posture used across {@code pivot-pilotage-core} for cross-tenant access
 * (CLAUDE.md §Isolation tenant). The read path ({@code resolveProfile}) has no controller yet
 * ({@code pivot-core-starter}/{@code TenantContext} not published, TODO-SETUP.md §5); its future
 * controller will map this exception identically.
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
