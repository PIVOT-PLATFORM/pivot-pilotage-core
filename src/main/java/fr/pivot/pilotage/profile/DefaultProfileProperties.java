package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Versioned default profile policy (EN18.10, frozen contract §c) — the fallback returned by
 * {@link OrganizationProfileResolver} when a tenant carries no explicit override row in
 * {@code pilotage.organization_profile}.
 *
 * <p>Bound from the {@code pivot.profile.default.*} configuration namespace with hard-coded,
 * documented defaults matching the fiche's proposed values (confirmed by the maintainer,
 * 2026-07-11): altitude = {@link Altitude#MACRO} (fast/macro roadmap — same value the temporary
 * {@code FixedDefaultAltitudeProvider} returns, preserving the projection contract), sovereignty =
 * {@link SovereigntyClass#ZONE_B_CONTROLEE} (ADR-015 zone B — contrôlée; "the most neutral class"
 * maps to standard controlled SaaS operation, not zone A — disproportionately restrictive as a
 * universal default — nor zone C — reserved for explicit third-party/DMZ exposure; see
 * {@link SovereigntyClass} Javadoc), rigor = {@link RigorLevel#STANDARD}, modules = the minimal
 * roadmap socle.
 *
 * <p>"Versioned" means: the default is code/config, resolved on the fly — never a fabricated DB
 * row. It is the substitution point the fiche calls "config versionnée résolue à la volée".
 */
@ConfigurationProperties(prefix = "pivot.profile.default")
public class DefaultProfileProperties {

    /** Default view altitude (render cursor opening notch). */
    private Altitude altitude = Altitude.MACRO;

    /** Default sovereignty class (ADR-015 zone B — contrôlée; see class Javadoc). */
    private SovereigntyClass sovereigntyClass = SovereigntyClass.ZONE_B_CONTROLEE;

    /** Default rigor level. */
    private RigorLevel rigorLevel = RigorLevel.STANDARD;

    /**
     * Default set of enabled module ids — the minimal roadmap socle (E22 roadmap module only, no
     * AP/CP nor PPI), per the fiche. A {@link LinkedHashSet} keeps a stable, documented order.
     */
    private Set<String> modules = new LinkedHashSet<>(Set.of("roadmap"));

    /**
     * Returns the default view altitude.
     *
     * @return the default {@link Altitude}
     */
    public Altitude getAltitude() {
        return altitude;
    }

    /**
     * Sets the default view altitude.
     *
     * @param altitude the altitude to set
     */
    public void setAltitude(final Altitude altitude) {
        this.altitude = altitude;
    }

    /**
     * Returns the default sovereignty class.
     *
     * @return the default {@link SovereigntyClass}
     */
    public SovereigntyClass getSovereigntyClass() {
        return sovereigntyClass;
    }

    /**
     * Sets the default sovereignty class.
     *
     * @param sovereigntyClass the sovereignty class to set
     */
    public void setSovereigntyClass(final SovereigntyClass sovereigntyClass) {
        this.sovereigntyClass = sovereigntyClass;
    }

    /**
     * Returns the default rigor level.
     *
     * @return the default {@link RigorLevel}
     */
    public RigorLevel getRigorLevel() {
        return rigorLevel;
    }

    /**
     * Sets the default rigor level.
     *
     * @param rigorLevel the rigor level to set
     */
    public void setRigorLevel(final RigorLevel rigorLevel) {
        this.rigorLevel = rigorLevel;
    }

    /**
     * Returns an unmodifiable copy of the default module ids (defensive copy — SpotBugs
     * {@code EI_EXPOSE_REP}).
     *
     * @return the default module ids
     */
    public Set<String> getModules() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(modules));
    }

    /**
     * Sets the default module ids (defensive copy — SpotBugs {@code EI_EXPOSE_REP2}).
     *
     * @param modules the module ids to set
     */
    public void setModules(final Set<String> modules) {
        this.modules = modules == null ? new LinkedHashSet<>() : new LinkedHashSet<>(modules);
    }

    /**
     * Materializes the versioned default into an immutable {@link DefaultOrganizationProfile}.
     *
     * @return the versioned default profile
     */
    public DefaultOrganizationProfile toProfile() {
        return new DefaultOrganizationProfile(altitude, sovereigntyClass, rigorLevel, getModules());
    }
}
