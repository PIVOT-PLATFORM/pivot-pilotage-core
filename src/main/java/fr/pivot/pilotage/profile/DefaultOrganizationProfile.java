package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable default organization profile (EN18.10, frozen contract §c) — the value returned by
 * {@link OrganizationProfileResolver#resolveProfile(long)}.
 *
 * <p><strong>Profile = policy, not engine.</strong> The four attributes are a stable, deterministic
 * per-tenant policy read by downstream consumers (E22 view cursor via the
 * {@code DefaultAltitudeProvider} seam, E03 module activation). The altitude only parametrizes the
 * <em>projection</em> of the single temporal graph (EN22.1c) — it never mutates the graph and the
 * scheduling engine never consumes it.
 *
 * <p><strong>Substitutability.</strong> This record's shape is the stable contract E40 will
 * implement: the default backing (EN18.10, versioned constants + optional DB override) and the
 * adaptive profile engine (E40, locked) produce the <em>same</em> {@code
 * DefaultOrganizationProfile} without any change to the consumers.
 *
 * <p><strong>Gap — module activation.</strong> {@code defaultModules} carries the default
 * <em>set</em> of module ids; the actual wiring to the module registry / feature activation is a
 * property of {@code pivot-core} (E03) exposed via {@code pivot-core-starter}, which is not yet
 * published (CLAUDE.md §gap, TODO-SETUP §5). This enabler therefore only <em>carries</em> the
 * default set, it does not activate anything.
 *
 * @param altitude         the default view altitude / render cursor (never {@code null})
 * @param sovereigntyClass the default sovereignty class (never {@code null})
 * @param rigorLevel       the default rigor level (never {@code null})
 * @param defaultModules   the default set of enabled module ids (never {@code null}; defensively
 *                         copied to an unmodifiable set)
 */
public record DefaultOrganizationProfile(
        Altitude altitude,
        SovereigntyClass sovereigntyClass,
        RigorLevel rigorLevel,
        Set<String> defaultModules) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code defaultModules} so the
     * record is fully immutable (SpotBugs {@code EI_EXPOSE_REP} — applied preventively, the record
     * accessor pattern only surfaces under {@code --release 24}).
     *
     * @throws NullPointerException if any attribute is {@code null} (a profile is never fabricated
     *                              with a missing deterministic attribute)
     */
    public DefaultOrganizationProfile {
        Objects.requireNonNull(altitude, "altitude");
        Objects.requireNonNull(sovereigntyClass, "sovereigntyClass");
        Objects.requireNonNull(rigorLevel, "rigorLevel");
        defaultModules = Set.copyOf(Objects.requireNonNull(defaultModules, "defaultModules"));
    }
}
