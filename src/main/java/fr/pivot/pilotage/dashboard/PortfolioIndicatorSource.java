package fr.pivot.pilotage.dashboard;

import java.util.Optional;

/**
 * Cross-cutting extension point (SPI) supplying the tension overlay ("avancement"/"météo" alerts,
 * AC2) rendered on {@link DashboardWidgetType#PORTFOLIO_STATUS_SUMMARY} and
 * {@link DashboardWidgetType#WEATHER_ALERTS} widgets — mirrors
 * {@code fr.pivot.pilotage.consolidation.ApplicationDataContributor}'s SPI-plus-no-op-default idiom
 * exactly (EN18.9, ADR-006/ADR-008 posture applied here too: the threshold/tension computation
 * itself is owned elsewhere, never re-derived by this module).
 *
 * <p><strong>Deliberate decoupling from two not-yet-merged items</strong> (documented per this US's
 * task brief):
 * <ul>
 *   <li>The backlog file's "Hors périmètre" explicitly delegates threshold computation ("retard,
 *       dépassement, surcharge") to the future US23.2.4 "météo" calc source — this module never
 *       invents that logic, it only renders whatever a real implementation eventually reports.</li>
 *   <li>US23.2.2 does <strong>not</strong> block on US23.2.1 ("vue portefeuille consolidée"): the
 *       {@link DashboardWidgetType#PORTFOLIO_STATUS_SUMMARY}/{@code STRATEGIC_MILESTONES} widgets
 *       read the already-merged EN18.9 {@code ApplicationConsolidationService} directly (a single
 *       application's roll-up); this SPI only carries the <em>tension overlay</em>, decoupled from
 *       whatever multi-application aggregate US23.2.1 ends up producing. Once US23.2.1 merges, a
 *       future enhancement could add a portfolio-wide widget type consuming its aggregate — out of
 *       scope here, no assumption made about its shape.</li>
 * </ul>
 *
 * <p>Only {@link NoOpPortfolioIndicatorSource} is wired today; every widget reads
 * {@link IndicatorStatus#UNAVAILABLE} for its tension overlay until a real bean is added — an
 * honest reflection of "no threshold engine exists yet", not a bug. A future implementation plugs
 * in as an additional Spring bean; {@link DashboardService} needs no change.
 */
public interface PortfolioIndicatorSource {

    /**
     * Reports this source's tension snapshot for one application, if it has one.
     *
     * @param tenantId      the requesting tenant's {@code public.tenants.id} — an implementation
     *                      must never return data belonging to another tenant
     * @param applicationId the application to report tension for
     * @param kind          which indicator family is being asked for
     * @return the tension snapshot, or {@link Optional#empty()} if this source has nothing to
     *         report for that application/kind
     */
    Optional<PortfolioIndicatorSnapshot> indicatorFor(long tenantId, long applicationId,
            PortfolioIndicatorKind kind);
}
