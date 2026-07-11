package fr.pivot.pilotage.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Application} entities (EN18.1).
 *
 * <p>Provides standard CRUD operations through {@link JpaRepository} plus tenant-scoped
 * lookups. All read paths used by future endpoints must go through a tenant-scoped variant to
 * preserve multi-tenant isolation.
 */
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    /**
     * Finds all applications belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the applications owned by that tenant (possibly empty)
     */
    List<Application> findAllByTenantId(Long tenantId);

    /**
     * Finds all applications belonging to the given tenant and team (per-team portfolio scoping,
     * team_id retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the applications owned by that tenant/team (possibly empty)
     */
    List<Application> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds an application by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the application does not exist or belongs to a
     * different tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the application id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the application, or empty if not found
     */
    Optional<Application> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Finds an application by its identifier, verifying it belongs to the given tenant and team
     * (per-team portfolio scoping, team_id retrofit).
     *
     * @param id       the application id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param teamId   the expected team's {@code public.teams.id}
     * @return an {@link Optional} containing the application, or empty if not found
     */
    Optional<Application> findByIdAndTenantIdAndTeamId(Long id, Long tenantId, Long teamId);
}
