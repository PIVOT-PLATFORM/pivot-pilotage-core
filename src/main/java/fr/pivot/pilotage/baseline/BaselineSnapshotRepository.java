package fr.pivot.pilotage.baseline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link BaselineSnapshot} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant- and baseline-scoped lookups to preserve multi-tenant isolation.
 */
public interface BaselineSnapshotRepository extends JpaRepository<BaselineSnapshot, Long> {

    /**
     * Finds all snapshots of the given baseline within the given tenant.
     *
     * @param baselineId the parent {@code pilotage.baseline.id}
     * @param tenantId   the {@code public.tenants.id} to restrict results to
     * @return the snapshots (possibly empty)
     */
    List<BaselineSnapshot> findAllByBaselineIdAndTenantId(Long baselineId, Long tenantId);

    /**
     * Finds all snapshots of the given baseline within the given tenant and team (per-team
     * portfolio scoping, team_id retrofit).
     *
     * @param baselineId the parent {@code pilotage.baseline.id}
     * @param tenantId   the {@code public.tenants.id} to restrict results to
     * @param teamId     the {@code public.teams.id} to restrict results to
     * @return the snapshots (possibly empty)
     */
    List<BaselineSnapshot> findAllByBaselineIdAndTenantIdAndTeamId(Long baselineId, Long tenantId, Long teamId);

    /**
     * Counts the snapshots of the given baseline within the given tenant and team (US22.4.9) — used
     * to report {@code taskCount} on {@code fr.pivot.pilotage.baseline.BaselineResponse} without
     * loading every snapshot row.
     *
     * @param baselineId the parent {@code pilotage.baseline.id}
     * @param tenantId   the {@code public.tenants.id} to restrict results to
     * @param teamId     the {@code public.teams.id} to restrict results to
     * @return the number of snapshots belonging to that baseline
     */
    long countByBaselineIdAndTenantIdAndTeamId(Long baselineId, Long tenantId, Long teamId);
}
