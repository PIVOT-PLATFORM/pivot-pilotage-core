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
}
