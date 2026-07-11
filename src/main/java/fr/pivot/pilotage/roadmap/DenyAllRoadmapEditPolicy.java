package fr.pivot.pilotage.roadmap;

import org.springframework.stereotype.Component;

/**
 * Fail-closed default {@link RoadmapEditPolicy} (US22.3.1) — always denies, wired as the only bean
 * until {@code pivot-core-starter} publishes project membership/roles (CLAUDE.md §gap,
 * {@code TODO-SETUP.md} §5). Mirrors
 * {@code fr.pivot.pilotage.profile.DenyAllOrganizationProfileOverridePolicy} (EN18.10 écart #3)
 * exactly.
 *
 * <p>This is <strong>not</strong> a hardcoded role and <strong>not</strong> a silent bypass: it is
 * the explicit, documented absence of an authorization mechanism, made safe by refusing every
 * write rather than guessing or permitting everyone. Replace this bean with a real
 * membership-checking implementation once the starter is consumable; {@link RoadmapController}
 * requires no change.
 */
@Component
public class DenyAllRoadmapEditPolicy implements RoadmapEditPolicy {

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
