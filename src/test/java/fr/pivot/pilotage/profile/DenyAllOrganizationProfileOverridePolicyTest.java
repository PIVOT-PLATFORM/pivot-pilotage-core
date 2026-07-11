package fr.pivot.pilotage.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO test for {@link DenyAllOrganizationProfileOverridePolicy} (EN18.10 écart #3) — the
 * fail-closed placeholder wired until {@code pivot-core-starter} publishes roles. Its whole
 * contract is "always deny", verified directly (no Spring context needed).
 */
class DenyAllOrganizationProfileOverridePolicyTest {

    @Test
    void isAuthorizedIsAlwaysFalse() {
        final DenyAllOrganizationProfileOverridePolicy policy = new DenyAllOrganizationProfileOverridePolicy();

        assertThat(policy.isAuthorized()).isFalse();
        // Deterministic — no hidden state, calling it again does not flip the answer.
        assertThat(policy.isAuthorized()).isFalse();
    }
}
