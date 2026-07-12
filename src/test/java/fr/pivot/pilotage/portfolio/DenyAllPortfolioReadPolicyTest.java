package fr.pivot.pilotage.portfolio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO test for {@link DenyAllPortfolioReadPolicy} (US23.2.1) — the fail-closed placeholder
 * wired until {@code pivot-core-starter} publishes project membership/roles. Its whole contract is
 * "always deny", verified directly (no Spring context needed) — mirrors
 * {@code fr.pivot.pilotage.roadmap.DenyAllRoadmapEditPolicyTest}.
 */
class DenyAllPortfolioReadPolicyTest {

    @Test
    void isAuthorizedIsAlwaysFalse() {
        final DenyAllPortfolioReadPolicy policy = new DenyAllPortfolioReadPolicy();

        assertThat(policy.isAuthorized()).isFalse();
        // Deterministic — no hidden state, calling it again does not flip the answer.
        assertThat(policy.isAuthorized()).isFalse();
    }
}
