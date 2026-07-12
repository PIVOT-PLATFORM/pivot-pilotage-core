package fr.pivot.pilotage.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DashboardConfig} entities (US23.2.2).
 */
public interface DashboardConfigRepository extends JpaRepository<DashboardConfig, Long> {

    /**
     * Finds a user's dashboard, scoped to tenant and team (multi-tenant isolation; see
     * {@link DashboardConfig} class doc for why {@code teamId} is a genuine resolution dimension
     * here).
     *
     * <p>This is the sole read/write entry point used by {@link DashboardService}: a request
     * scoped to a given {@code userId} can only ever observe/mutate <em>that same</em>
     * {@code userId}'s row — a different user's real, configured dashboard is structurally
     * unreachable through this method (US23.2.2 security AC — "propre à l'utilisateur").
     *
     * @param tenantId the {@code public.tenants.id} to restrict the result to
     * @param teamId   the {@code public.teams.id} to restrict the result to
     * @param userId   the owning user's id
     * @return the dashboard, or {@link Optional#empty()} if this user has not configured one yet
     *         under this tenant/team
     */
    Optional<DashboardConfig> findByTenantIdAndTeamIdAndUserId(Long tenantId, Long teamId, Long userId);
}
