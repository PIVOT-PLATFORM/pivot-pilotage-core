package fr.pivot.pilotage.weather;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable, normalized weather indicator of a single project (US23.2.4) — the reusable
 * unit both US23.2.1 (consolidated portfolio view) and US23.2.2 (customizable dashboards) are
 * meant to consume rather than each recomputing their own divergent variant (backlog note: "le
 * calcul doit être exposé via une API/entité réutilisable plutôt que dupliqué dans chaque vue").
 *
 * <p>No collection field — every component is an immutable value type (primitive, {@link
 * BigDecimal}, {@link LocalDate}, enum), so no defensive copy is needed in the canonical
 * constructor (unlike {@code ApplicationConsolidation} in the sibling {@code consolidation}
 * package, EN18.9).
 *
 * @param projectId               the evaluated project's id
 * @param tenantId                the owning tenant's {@code public.tenants.id} (isolation
 *                                boundary — the calculation is always filtered by tenant)
 * @param status                  the normalized {@link ProjectWeatherStatus}; never {@code null}
 * @param actualProgressPercent   average temporal completion across the project's leaf tasks with
 *                                a progress record (0–100), or {@code null} when {@code status}
 *                                is {@link ProjectWeatherStatus#INDETERMINATE}
 * @param expectedProgressPercent progress expected at {@code asOfDate} given the project's
 *                                temporal window, homogeneous linear model (0–100), or {@code
 *                                null} when {@code status} is {@link
 *                                ProjectWeatherStatus#INDETERMINATE}
 * @param varianceInPoints        {@code actualProgressPercent - expectedProgressPercent}, in
 *                                percentage points (negative = behind schedule), or {@code null}
 *                                when {@code status} is {@link ProjectWeatherStatus#INDETERMINATE}
 * @param asOfDate                the project's {@code statusDate} (EN22.1a) used as the
 *                                evaluation reference, or {@code null} when missing
 * @param indeterminateReason     why the status is {@link ProjectWeatherStatus#INDETERMINATE}, or
 *                                {@code null} for any other status
 */
public record ProjectWeather(
        long projectId,
        long tenantId,
        ProjectWeatherStatus status,
        BigDecimal actualProgressPercent,
        BigDecimal expectedProgressPercent,
        BigDecimal varianceInPoints,
        LocalDate asOfDate,
        ProjectWeatherIndeterminateReason indeterminateReason) {

    /**
     * Canonical constructor validating {@code status} is never {@code null}.
     *
     * @throws NullPointerException if {@code status} is {@code null}
     */
    public ProjectWeather {
        Objects.requireNonNull(status, "status");
    }
}
