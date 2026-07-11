package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Pure-POJO tests for the EN18.10 profile value type and its policy enums: the four deterministic
 * non-null attributes, the defensive copy of {@code defaultModules}, and the versioned default
 * materialization from {@link DefaultProfileProperties}.
 */
class DefaultOrganizationProfileTest {

    @Test
    void carriesFourNonNullAttributes() {
        final DefaultOrganizationProfile profile = new DefaultOrganizationProfile(
                Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, Set.of("roadmap"));

        assertThat(profile.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(profile.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_B_CONTROLEE);
        assertThat(profile.rigorLevel()).isEqualTo(RigorLevel.STANDARD);
        assertThat(profile.defaultModules()).containsExactly("roadmap");
    }

    @Test
    void rejectsAnyNullAttribute() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultOrganizationProfile(
                null, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, Set.of()));
        assertThatNullPointerException().isThrownBy(() -> new DefaultOrganizationProfile(
                Altitude.MACRO, null, RigorLevel.STANDARD, Set.of()));
        assertThatNullPointerException().isThrownBy(() -> new DefaultOrganizationProfile(
                Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, null, Set.of()));
        assertThatNullPointerException().isThrownBy(() -> new DefaultOrganizationProfile(
                Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, null));
    }

    @Test
    void defaultModulesAreDefensivelyCopiedAndUnmodifiable() {
        final Set<String> source = new HashSet<>();
        source.add("roadmap");
        final DefaultOrganizationProfile profile = new DefaultOrganizationProfile(
                Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, source);

        // Mutating the source must not affect the profile.
        source.add("gantt");
        assertThat(profile.defaultModules()).containsExactly("roadmap");

        // The exposed set is unmodifiable.
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> profile.defaultModules().add("x"));
    }

    @Test
    void versionedDefaultsMatchFicheProposedValues() {
        final DefaultProfileProperties props = new DefaultProfileProperties();
        final DefaultOrganizationProfile profile = props.toProfile();

        assertThat(profile.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(profile.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_B_CONTROLEE);
        assertThat(profile.rigorLevel()).isEqualTo(RigorLevel.STANDARD);
        assertThat(profile.defaultModules()).containsExactly("roadmap");
    }

    @Test
    void propertiesSettersRebindAndCopyDefensively() {
        final DefaultProfileProperties props = new DefaultProfileProperties();
        props.setAltitude(Altitude.DETAIL);
        props.setSovereigntyClass(SovereigntyClass.ZONE_A_SOUVERAINE);
        props.setRigorLevel(RigorLevel.STRICT);
        final Set<String> mods = new HashSet<>(Set.of("roadmap", "gantt"));
        props.setModules(mods);
        mods.add("ppi");

        final DefaultOrganizationProfile profile = props.toProfile();
        assertThat(profile.altitude()).isEqualTo(Altitude.DETAIL);
        assertThat(profile.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_A_SOUVERAINE);
        assertThat(profile.rigorLevel()).isEqualTo(RigorLevel.STRICT);
        assertThat(profile.defaultModules()).containsExactlyInAnyOrder("roadmap", "gantt");
        assertThat(props.getModules()).doesNotContain("ppi");
    }

    @Test
    void nullModulesSetterYieldsEmptySet() {
        final DefaultProfileProperties props = new DefaultProfileProperties();
        props.setModules(null);
        assertThat(props.toProfile().defaultModules()).isEmpty();
    }

    @Test
    void policyEnumsExposeExpectedConstants() {
        assertThat(SovereigntyClass.values())
                .containsExactly(SovereigntyClass.ZONE_A_SOUVERAINE, SovereigntyClass.ZONE_B_CONTROLEE,
                        SovereigntyClass.ZONE_C_DMZ_EXTERNE);
        assertThat(RigorLevel.values())
                .containsExactly(RigorLevel.LIGHT, RigorLevel.STANDARD, RigorLevel.STRICT);
    }

    @Test
    void tenantNotFoundExceptionCarriesId() {
        assertThat(new TenantNotFoundException(42L)).hasMessageContaining("42");
    }
}
