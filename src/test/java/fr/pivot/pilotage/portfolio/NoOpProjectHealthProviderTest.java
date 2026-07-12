package fr.pivot.pilotage.portfolio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO test for {@link NoOpProjectHealthProvider} (US23.2.1) — mirrors
 * {@code fr.pivot.pilotage.consolidation.NoOpApplicationDataContributor}'s test: the whole contract
 * is "always empty", so {@link PortfolioConsolidationService} falls back to the explicit
 * {@link ProjectHealthStatus#NOT_SET} (error AC).
 */
class NoOpProjectHealthProviderTest {

    @Test
    void healthOfIsAlwaysEmpty() {
        final NoOpProjectHealthProvider provider = new NoOpProjectHealthProvider();

        assertThat(provider.healthOf(1L, 2L)).isEmpty();
    }
}
