package fr.pivot.pilotage.portfolio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-POJO tests for {@link ProjectHealthIndicator} (US23.2.1) — the {@link
 * ProjectHealthIndicator#notSet()} factory and the null-guard on the canonical constructor.
 */
class ProjectHealthIndicatorTest {

    @Test
    void notSet_carriesTheExplicitNotSetStatus() {
        assertThat(ProjectHealthIndicator.notSet().status()).isEqualTo(ProjectHealthStatus.NOT_SET);
    }

    @Test
    void constructor_rejectsNullStatus() {
        assertThatThrownBy(() -> new ProjectHealthIndicator(null))
                .isInstanceOf(NullPointerException.class);
    }
}
