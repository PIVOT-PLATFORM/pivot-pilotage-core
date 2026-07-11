package fr.pivot.pilotage.roadmap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO test for {@link DenyAllRoadmapEditPolicy} (US22.3.1) — the fail-closed placeholder
 * wired until {@code pivot-core-starter} publishes project membership/roles. Its whole contract is
 * "always deny", verified directly (no Spring context needed) — mirrors
 * {@code fr.pivot.pilotage.profile.DenyAllOrganizationProfileOverridePolicyTest}.
 */
class DenyAllRoadmapEditPolicyTest {

    @Test
    void isAuthorizedIsAlwaysFalse() {
        final DenyAllRoadmapEditPolicy policy = new DenyAllRoadmapEditPolicy();

        assertThat(policy.isAuthorized()).isFalse();
        // Deterministic — no hidden state, calling it again does not flip the answer.
        assertThat(policy.isAuthorized()).isFalse();
    }
}
