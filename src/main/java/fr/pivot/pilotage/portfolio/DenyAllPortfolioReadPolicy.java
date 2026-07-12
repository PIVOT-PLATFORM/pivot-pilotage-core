package fr.pivot.pilotage.portfolio;

import org.springframework.stereotype.Component;

/**
 * Fail-closed default {@link PortfolioReadPolicy} (US23.2.1) — always denies, wired as the only
 * bean until {@code pivot-core-starter} publishes project membership/roles (CLAUDE.md §gap,
 * {@code TODO-SETUP.md} §5). Mirrors {@code fr.pivot.pilotage.roadmap.DenyAllRoadmapEditPolicy}
 * exactly.
 *
 * <p>This is <strong>not</strong> a hardcoded role and <strong>not</strong> a silent bypass: it is
 * the explicit, documented absence of an authorization mechanism, made safe by refusing every read
 * rather than guessing or permitting everyone. Replace this bean with a real membership-checking
 * implementation once the starter is consumable; {@link PortfolioController} requires no change.
 */
@Component
public class DenyAllPortfolioReadPolicy implements PortfolioReadPolicy {

    /**
     * {@inheritDoc}
     *
     * @return always {@code false} — no membership mechanism is wired yet
     */
    @Override
    public boolean isAuthorized() {
        return false;
    }
}
