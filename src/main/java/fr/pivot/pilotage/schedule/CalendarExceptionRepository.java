package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CalendarException} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant- and calendar-scoped lookups to preserve multi-tenant isolation.
 */
public interface CalendarExceptionRepository extends JpaRepository<CalendarException, Long> {

    /**
     * Finds all exceptions of the given calendar within the given tenant.
     *
     * @param calendarId the parent {@code pilotage.calendar.id}
     * @param tenantId   the {@code public.tenants.id} to restrict results to
     * @return the exceptions (possibly empty)
     */
    List<CalendarException> findAllByCalendarIdAndTenantId(Long calendarId, Long tenantId);

    /**
     * Finds all exceptions of the given calendar within the given tenant and team (per-team
     * portfolio scoping, team_id retrofit).
     *
     * @param calendarId the parent {@code pilotage.calendar.id}
     * @param tenantId   the {@code public.tenants.id} to restrict results to
     * @param teamId     the {@code public.teams.id} to restrict results to
     * @return the exceptions (possibly empty)
     */
    List<CalendarException> findAllByCalendarIdAndTenantIdAndTeamId(Long calendarId, Long tenantId, Long teamId);

    /**
     * Finds a single exception by id, verifying it belongs to the given calendar within the given
     * tenant and team — the compound isolation lookup used before a scoped delete/update.
     *
     * @param id         the exception id
     * @param calendarId the parent {@code pilotage.calendar.id}
     * @param tenantId   the expected tenant's {@code public.tenants.id}
     * @param teamId     the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the exception, or empty if not found for that scope
     */
    Optional<CalendarException> findByIdAndCalendarIdAndTenantIdAndTeamId(Long id, Long calendarId,
            Long tenantId, Long teamId);
}
