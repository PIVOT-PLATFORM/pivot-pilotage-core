package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Calendar} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant-scoped lookups; every read path used by future endpoints must go
 * through a tenant-scoped variant to preserve multi-tenant isolation.
 */
public interface CalendarRepository extends JpaRepository<Calendar, Long> {

    /**
     * Finds all calendars belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the calendars owned by that tenant (possibly empty)
     */
    List<Calendar> findAllByTenantId(Long tenantId);

    /**
     * Finds all calendars belonging to the given tenant and team (per-team portfolio scoping,
     * team_id retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the calendars owned by that tenant/team (possibly empty)
     */
    List<Calendar> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds a calendar by id, verifying it belongs to the given tenant.
     *
     * @param id       the calendar id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} with the calendar, or empty if not found for that tenant
     */
    Optional<Calendar> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Finds a calendar by id, verifying it belongs to the given tenant and team (per-team
     * portfolio scoping, team_id retrofit).
     *
     * @param id       the calendar id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param teamId   the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the calendar, or empty if not found for that tenant/team
     */
    Optional<Calendar> findByIdAndTenantIdAndTeamId(Long id, Long tenantId, Long teamId);
}
