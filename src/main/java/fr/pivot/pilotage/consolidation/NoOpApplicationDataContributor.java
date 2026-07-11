package fr.pivot.pilotage.consolidation;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Default, no-op {@link ApplicationDataContributor} (EN18.9). With no cross-module bus wired yet
 * (CLAUDE.md §gap, TODO-SETUP §5), the consolidation must still be well-defined: this bean
 * contributes nothing, so the {@link ApplicationConsolidation} carries the pilotage-owned aggregate
 * alone. It exists so the SPI is never an empty collection at runtime and so its presence in the
 * consolidation pipeline is exercised (and provable) end to end.
 *
 * <p>A real, bus-backed contributor (in a post-starter US) is added as an additional bean; both
 * coexist and are merged by {@link ApplicationConsolidationService}.
 */
@Component
public class NoOpApplicationDataContributor implements ApplicationDataContributor {

    /**
     * {@inheritDoc}
     *
     * @return always {@link Optional#empty()} — the no-op default contributes nothing
     */
    @Override
    public Optional<ApplicationAggregateContribution> contribute(final long tenantId,
            final long applicationId) {
        return Optional.empty();
    }
}
