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
     * Finds a calendar by id, verifying it belongs to the given tenant.
     *
     * @param id       the calendar id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} with the calendar, or empty if not found for that tenant
     */
    Optional<Calendar> findByIdAndTenantId(Long id, Long tenantId);
}
