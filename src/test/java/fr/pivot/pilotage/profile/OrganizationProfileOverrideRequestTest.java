package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Pure-POJO tests for {@link OrganizationProfileOverrideRequest} (EN18.10 écart #3) — the compact
 * constructor's {@code defaultModules} normalization and defensive copy, exercised without a
 * Spring context or bean validation (validation itself is covered at the controller layer,
 * {@link OrganizationProfileControllerTest}).
 */
class OrganizationProfileOverrideRequestTest {

    @Test
    void nullDefaultModulesNormalizedToEmptySet() {
        final OrganizationProfileOverrideRequest request = new OrganizationProfileOverrideRequest(
                1L, Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, null);

        assertThat(request.defaultModules()).isEmpty();
    }

    @Test
    void defaultModulesAreDefensivelyCopiedAndUnmodifiable() {
        final Set<String> source = new HashSet<>();
        source.add("roadmap");
        final OrganizationProfileOverrideRequest request = new OrganizationProfileOverrideRequest(
                1L, Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, source);

        // Mutating the source must not affect the request.
        source.add("gantt");
        assertThat(request.defaultModules()).containsExactly("roadmap");

        // The exposed set is unmodifiable.
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> request.defaultModules().add("x"));
    }

    @Test
    void carriesAllAttributes() {
        final OrganizationProfileOverrideRequest request = new OrganizationProfileOverrideRequest(
                7L, Altitude.DETAIL, SovereigntyClass.ZONE_A_SOUVERAINE, RigorLevel.STRICT,
                Set.of("roadmap", "gantt"));

        assertThat(request.teamId()).isEqualTo(7L);
        assertThat(request.altitude()).isEqualTo(Altitude.DETAIL);
        assertThat(request.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_A_SOUVERAINE);
        assertThat(request.rigorLevel()).isEqualTo(RigorLevel.STRICT);
        assertThat(request.defaultModules()).containsExactlyInAnyOrder("roadmap", "gantt");
    }
}
