package fr.pivot.pilotage.profile;

import org.springframework.stereotype.Component;

/**
 * Fail-closed default {@link OrganizationProfileOverridePolicy} (EN18.10 écart #3) — always
 * denies, wired as the only bean until {@code pivot-core-starter} publishes roles (CLAUDE.md
 * §gap, {@code TODO-SETUP.md} §5).
 *
 * <p>This is <strong>not</strong> a hardcoded role and <strong>not</strong> a silent bypass: it
 * is the explicit, documented absence of an authorization mechanism, made safe by refusing every
 * request rather than guessing or permitting everyone. Replace this bean with a real
 * role-checking implementation once the starter is consumable; {@link OrganizationProfileController}
 * requires no change.
 */
@Component
public class DenyAllOrganizationProfileOverridePolicy implements OrganizationProfileOverridePolicy {

    /**
     * {@inheritDoc}
     *
     * @return always {@code false} — no role mechanism is wired yet
     */
    @Override
    public boolean isAuthorized() {
        return false;
    }
}
