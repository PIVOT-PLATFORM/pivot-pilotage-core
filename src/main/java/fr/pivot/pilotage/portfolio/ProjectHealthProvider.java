package fr.pivot.pilotage.portfolio;

import java.util.Optional;

/**
 * Extension point (SPI) for a project's health/weather indicator (US23.2.1 <em>consumes</em>;
 * US23.2.4 "météo/indicateurs" <em>computes and contributes</em>) — mirrors {@code
 * fr.pivot.pilotage.consolidation.ApplicationDataContributor}'s established seam pattern exactly,
 * per the same "reuse this consolidation mechanism" instruction EN18.9 set: the portfolio view never
 * calculates a health formula itself (out of scope, "Hors périmètre" of this US's Gate 1 file), it
 * only consumes whatever a registered provider contributes.
 *
 * <p><strong>Wiring.</strong> Not a cross-module bus dependency (health stays inside the
 * {@code pilotage} module, unlike EN18.9's budget/risk contributors which cross into other PIVOT
 * modules) — a same-module extension point instead, deliberately kept provider-agnostic so this
 * US's contract never had to change once US23.2.4 landed. {@link WeatherProjectHealthProvider} now
 * contributes the real, computed indicator (US23.2.4's {@code ProjectWeatherService}, wired
 * unchanged); {@link NoOpProjectHealthProvider} remains registered alongside it as a defensive,
 * always-empty fallback. Either way, a project with no contributed indicator reports the explicit
 * {@link ProjectHealthStatus#NOT_SET} — the error AC ("non renseigné", never omitted, never a
 * misleading default) — and {@link PortfolioConsolidationService} needed no change to consume it.
 */
public interface ProjectHealthProvider {

    /**
     * Resolves this provider's health indicator for a single project, if it has one.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id} (isolation boundary — an
     *                  implementation must never return data belonging to another tenant)
     * @param projectId the project to resolve a health indicator for
     * @return the indicator, or {@link Optional#empty()} if this provider has none for the project
     */
    Optional<ProjectHealthIndicator> healthOf(long tenantId, long projectId);
}
