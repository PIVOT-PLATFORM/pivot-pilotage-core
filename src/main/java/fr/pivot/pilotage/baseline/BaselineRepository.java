package fr.pivot.pilotage.baseline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Baseline} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant- and project-scoped lookups to preserve multi-tenant isolation.
 */
public interface BaselineRepository extends JpaRepository<Baseline, Long> {

    /**
     * Finds all baselines belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the baselines owned by that tenant (possibly empty)
     */
    List<Baseline> findAllByTenantId(Long tenantId);

    /**
     * Finds all baselines belonging to the given tenant and team (per-team portfolio scoping,
     * team_id retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the baselines owned by that tenant/team (possibly empty)
     */
    List<Baseline> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds all baselines of the given project within the given tenant.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @return the baselines (possibly empty)
     */
    List<Baseline> findAllByProjectIdAndTenantId(Long projectId, Long tenantId);

    /**
     * Finds all baselines of the given project within the given tenant and team (per-team
     * portfolio scoping, team_id retrofit).
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the baselines (possibly empty)
     */
    List<Baseline> findAllByProjectIdAndTenantIdAndTeamId(Long projectId, Long tenantId, Long teamId);

    /**
     * Finds the baseline set at the given index for the given project within the given tenant and
     * team (US22.4.9) — the single lookup shared by the pose/overwrite, delete, variance and compare
     * operations of {@code fr.pivot.pilotage.baseline.BaselineService}.
     *
     * @param projectId     the parent {@code pilotage.project.id}
     * @param tenantId      the {@code public.tenants.id} to restrict results to
     * @param teamId        the {@code public.teams.id} to restrict results to
     * @param baselineIndex the baseline slot (0..10, MS Project parity)
     * @return an {@link Optional} with the baseline, or empty if no baseline is set at that index
     */
    Optional<Baseline> findByProjectIdAndTenantIdAndTeamIdAndBaselineIndex(Long projectId, Long tenantId,
            Long teamId, Short baselineIndex);
}
