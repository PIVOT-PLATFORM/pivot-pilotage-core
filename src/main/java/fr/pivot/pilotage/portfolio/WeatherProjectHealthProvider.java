package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ProjectNotFoundException;
import fr.pivot.pilotage.weather.ProjectWeather;
import fr.pivot.pilotage.weather.ProjectWeatherService;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link ProjectHealthProvider} backed by US23.2.4's normalized weather calculation
 * ({@link ProjectWeatherService}) — the "santé/météo" indicator this US (US23.2.1)
 * <strong>consumes but does not compute</strong> (Hors périmètre: "le calcul détaillé de
 * l'indicateur de santé/météo est défini par US23.2.4 ; cette US consomme l'indicateur, elle ne le
 * calcule pas"). Delegates entirely to {@link ProjectWeatherService#computeWeather(long, long)} —
 * no recomputation, no divergent formula (US23.2.4 backlog note: "le calcul doit être exposé via
 * une API/entité réutilisable plutôt que dupliqué dans chaque vue").
 *
 * <p><strong>Vocabulary mapping.</strong> {@link ProjectHealthStatus} is this US's own,
 * provider-agnostic vocabulary (the {@link ProjectHealthProvider} SPI predates US23.2.4 landing
 * and is deliberately not coupled to any single computation) — {@link
 * fr.pivot.pilotage.weather.ProjectWeatherStatus#SUNNY}/{@code CLOUDY}/{@code STORMY} map
 * one-to-one to {@link ProjectHealthStatus#ON_TRACK}/{@code AT_RISK}/{@code CRITICAL}. {@link
 * fr.pivot.pilotage.weather.ProjectWeatherStatus#INDETERMINATE} resolves to {@link
 * Optional#empty()} so {@link PortfolioConsolidationService} falls through to the explicit
 * {@link ProjectHealthIndicator#notSet()} — the same "non renseigné" state (error AC), never a
 * second, diverging vocabulary for "no data".
 *
 * <p><strong>Security.</strong> {@code tenantId} is forwarded unchanged to {@link
 * ProjectWeatherService#computeWeather(long, long)}, which itself only ever reads through
 * tenant-scoped repositories (US23.2.4) — no cross-tenant data can reach this provider. A project
 * that turns out not to exist for the tenant (race with a concurrent delete between the caller's
 * project listing and this lookup) is treated the same as "no indicator available" — {@link
 * Optional#empty()} — rather than propagating the exception, since this provider is queried
 * per-project inside an already tenant-scoped portfolio consolidation and never on the request's
 * critical path for existence checks.
 */
@Component
public class WeatherProjectHealthProvider implements ProjectHealthProvider {

    private final ProjectWeatherService projectWeatherService;

    /**
     * Constructs the provider.
     *
     * @param projectWeatherService the US23.2.4 weather computation service, reused unchanged
     */
    public WeatherProjectHealthProvider(final ProjectWeatherService projectWeatherService) {
        this.projectWeatherService = projectWeatherService;
    }

    /**
     * {@inheritDoc}
     *
     * @return the weather-derived health indicator, or {@link Optional#empty()} when the weather
     *         is {@link fr.pivot.pilotage.weather.ProjectWeatherStatus#INDETERMINATE} or the
     *         project cannot be resolved for the tenant
     */
    @Override
    public Optional<ProjectHealthIndicator> healthOf(final long tenantId, final long projectId) {
        final ProjectWeather weather;
        try {
            weather = projectWeatherService.computeWeather(tenantId, projectId);
        } catch (final ProjectNotFoundException ex) {
            return Optional.empty();
        }
        return switch (weather.status()) {
            case SUNNY -> Optional.of(new ProjectHealthIndicator(ProjectHealthStatus.ON_TRACK));
            case CLOUDY -> Optional.of(new ProjectHealthIndicator(ProjectHealthStatus.AT_RISK));
            case STORMY -> Optional.of(new ProjectHealthIndicator(ProjectHealthStatus.CRITICAL));
            case INDETERMINATE -> Optional.empty();
        };
    }
}
