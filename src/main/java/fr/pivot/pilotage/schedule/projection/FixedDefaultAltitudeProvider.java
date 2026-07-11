package fr.pivot.pilotage.schedule.projection;

import org.springframework.stereotype.Component;

/**
 * Minimal, tenant-agnostic {@link DefaultAltitudeProvider} that always returns the macro altitude
 * (EN22.1c).
 *
 * <p><strong>Temporary stand-in for EN18.10.</strong> The frozen contract routes the default view
 * altitude through {@code resolveProfile(tenant).altitude}; that profile service (EN18.10) is not
 * yet implemented. This component makes the projection layer functional today by returning a fixed
 * macro default (the {@code QUARTER}-grained roadmap opening notch, expressed here as
 * {@link Altitude#MACRO}). <strong>EN18.10 will replace this bean</strong> with a profile-backed
 * implementation of {@link DefaultAltitudeProvider}, substitutable via E40, without any change to
 * {@link PlanProjectionService}.
 */
@Component
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
