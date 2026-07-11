package fr.pivot.pilotage.profile;

/**
 * Tenant-scoped resolver contract for the {@link DefaultOrganizationProfile} (EN18.10, frozen
 * contract §c) — the stable {@code resolveProfile(tenant)} read contract E22 (view cursor) and E03
 * (module activation) consume.
 *
 * <p><strong>Substitutable by E40 (EN18.10 écart #4).</strong> Extracted as an interface so E40's
 * adaptive profile engine can later provide its own implementation behind this exact contract,
 * without any change to the callers (E03, E22) — pure substitution, no signature change. The
 * default backing (EN18.10) is {@link DefaultOrganizationProfileResolver}; consumers should depend
 * on this interface, never on the concrete class.
 *
 * <p>Resolution rules (binding on every implementation):
 * <ul>
 *   <li>Unknown tenant (no {@code public.tenants} row) → {@link TenantNotFoundException} (HTTP 404
 *       equivalent) — never a fabricated phantom profile.</li>
 *   <li>Cross-tenant isolation: only the requested tenant's data is ever read.</li>
 *   <li>The four resolved attributes are always non-null and deterministic for a given tenant at a
 *       given point in time.</li>
 * </ul>
 *
 * <p><strong>Policy, not engine.</strong> The returned profile is a policy value; the altitude it
 * carries only parametrizes the projection (EN22.1c), it never feeds the scheduling engine.
 */
public interface OrganizationProfileResolver {

    /**
     * Resolves the default organization profile for a tenant.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the resolved {@link DefaultOrganizationProfile} — always with four non-null
     *         attributes
     * @throws TenantNotFoundException if no tenant exists for {@code tenantId} (HTTP 404
     *                                 equivalent)
     */
    DefaultOrganizationProfile resolveProfile(long tenantId);
}
