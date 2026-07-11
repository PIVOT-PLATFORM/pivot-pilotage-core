package fr.pivot.pilotage.schedule.projection;

/**
 * Minimal, tenant-agnostic {@link DefaultAltitudeProvider} that always returns the macro altitude
 * (EN22.1c).
 *
 * <p><strong>Test/bootstrap fallback (EN18.10).</strong> This was the temporary stand-in until
 * EN18.10 delivered {@link ProfileBackedAltitudeProvider}, now the active profile-backed default
 * bean. This class is <strong>no longer a Spring component</strong>: it is retained as a fixed,
 * dependency-free fallback for tests and bootstrap scenarios that need a deterministic macro
 * altitude without wiring the resolver/database. It returns the fixed macro default (the roadmap
 * opening notch, expressed here as {@link Altitude#MACRO}) — the same value the versioned default
 * profile carries, which is what preserves the projection contract when the seam is swapped.
 */
public class FixedDefaultAltitudeProvider implements DefaultAltitudeProvider {

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link Altitude#MACRO} regardless of the tenant — the macro (roadmap) opening notch;
     * EN18.10 will make this tenant-specific.
     */
    @Override
    public Altitude defaultAltitude(final long tenantId) {
        return Altitude.MACRO;
    }
}
