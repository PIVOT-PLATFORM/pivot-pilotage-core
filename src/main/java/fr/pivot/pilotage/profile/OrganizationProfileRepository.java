package fr.pivot.pilotage.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link OrganizationProfile} override rows (EN18.10).
 *
 * <p>The only read path is tenant-scoped: {@link #findByTenantId(Long)} returns the single optional
 * override for a tenant (the {@code UNIQUE} constraint guarantees at most one). The resolver never
 * reads by primary key across tenants, preserving cross-tenant isolation.
 */
public interface OrganizationProfileRepository extends JpaRepository<OrganizationProfile, Long> {

    /**
     * Finds the (at most one) profile override belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict to
     * @return the tenant's override, or {@link Optional#empty()} if none exists
     */
    Optional<OrganizationProfile> findByTenantId(Long tenantId);
}
