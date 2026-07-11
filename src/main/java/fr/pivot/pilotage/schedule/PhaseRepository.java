package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Phase} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant- and project-scoped lookups to preserve multi-tenant isolation.
 */
public interface PhaseRepository extends JpaRepository<Phase, Long> {

    /**
     * Finds all phases belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the phases owned by that tenant (possibly empty)
     */
    List<Phase> findAllByTenantId(Long tenantId);

    /**
     * Finds all phases belonging to the given tenant and team (per-team portfolio scoping,
     * team_id retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the phases owned by that tenant/team (possibly empty)
     */
    List<Phase> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds all phases of the given project within the given tenant.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @return the phases (possibly empty)
     */
    List<Phase> findAllByProjectIdAndTenantId(Long projectId, Long tenantId);

    /**
     * Finds all phases of the given project within the given tenant and team (per-team portfolio
     * scoping, team_id retrofit).
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the phases (possibly empty)
     */
    List<Phase> findAllByProjectIdAndTenantIdAndTeamId(Long projectId, Long tenantId, Long teamId);
}
