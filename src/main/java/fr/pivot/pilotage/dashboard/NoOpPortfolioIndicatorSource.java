package fr.pivot.pilotage.dashboard;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Default, no-op {@link PortfolioIndicatorSource} (US23.2.2) — mirrors
 * {@code fr.pivot.pilotage.consolidation.NoOpApplicationDataContributor} exactly. With no real
 * "avancement"/"météo" threshold engine wired yet (the backlog file's "Hors périmètre" delegates
 * that computation to the future US23.2.4), this bean reports nothing so every dashboard widget's
 * tension overlay is well-defined ({@link IndicatorStatus#UNAVAILABLE}) rather than throwing or
 * silently guessing.
 */
@Component
public class NoOpPortfolioIndicatorSource implements PortfolioIndicatorSource {

    /**
     * {@inheritDoc}
     *
     * @return always {@link Optional#empty()} — the no-op default reports no tension
     */
    @Override
    public Optional<PortfolioIndicatorSnapshot> indicatorFor(final long tenantId, final long applicationId,
            final PortfolioIndicatorKind kind) {
        return Optional.empty();
    }
}
