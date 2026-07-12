package fr.pivot.pilotage.portfolio;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Default, no-op {@link ProjectHealthProvider} (US23.2.1). Mirrors {@code
 * fr.pivot.pilotage.consolidation.NoOpApplicationDataContributor} exactly: contributes nothing.
 *
 * <p>Registered alongside {@link WeatherProjectHealthProvider} (US23.2.4's computed indicator,
 * wired unchanged) as a defensive fallback — harmless either way, since {@code
 * PortfolioConsolidationService#resolveHealth} already defaults to the explicit {@link
 * ProjectHealthStatus#NOT_SET} (error AC) once every registered provider answers empty; this bean
 * simply documents that "explicit absence" contract as a concrete, always-empty implementation
 * rather than relying solely on the empty-list case.
 */
@Component
public class NoOpProjectHealthProvider implements ProjectHealthProvider {

    /**
     * {@inheritDoc}
     *
     * @return always {@link Optional#empty()} — the no-op default contributes nothing
     */
    @Override
    public Optional<ProjectHealthIndicator> healthOf(final long tenantId, final long projectId) {
        return Optional.empty();
    }
}
