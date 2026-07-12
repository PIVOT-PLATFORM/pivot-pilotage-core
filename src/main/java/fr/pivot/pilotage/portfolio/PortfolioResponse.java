package fr.pivot.pilotage.portfolio;

import java.util.List;
import java.util.Objects;

/**
 * Immutable root of the consolidated portfolio view (US23.2.1) — "une vision 360° multi-projets
 * (santé, avancement, phases, jalons et dates clés) avec drill-down" for one tenant, grouped by
 * application.
 *
 * <p>Security AC: {@link #applications()} only ever contains applications (and, transitively,
 * projects) belonging to {@link #tenantId} — {@link PortfolioConsolidationService#consolidate(long)}
 * reads exclusively through tenant-scoped repositories, so no cross-tenant row can reach this
 * response.
 *
 * @param tenantId     the requesting tenant's {@code public.tenants.id} (isolation boundary)
 * @param applications the tenant's applications, each grouping its projects (immutable; possibly
 *                     empty for a tenant with no application yet)
 */
public record PortfolioResponse(long tenantId, List<PortfolioApplicationEntry> applications) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code applications} so the
     * response is fully immutable (SpotBugs {@code EI_EXPOSE_REP}).
     *
     * @throws NullPointerException if {@code applications} is {@code null}
     */
    public PortfolioResponse {
        applications = List.copyOf(Objects.requireNonNull(applications, "applications"));
    }
}
