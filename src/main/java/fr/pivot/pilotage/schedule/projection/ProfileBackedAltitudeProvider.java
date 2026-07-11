package fr.pivot.pilotage.schedule.projection;

import fr.pivot.pilotage.profile.OrganizationProfileResolver;
import org.springframework.stereotype.Component;

/**
 * Profile-backed {@link DefaultAltitudeProvider} (EN18.10) — the real backing of the EN22.1c seam.
 *
 * <p>The frozen contract routes the default view altitude through {@code
 * resolveProfile(tenant).altitude}. This bean is that wiring: it delegates to
 * {@link OrganizationProfileResolver#resolveProfile(long)} and returns the resolved profile's
 * altitude, without changing the projection signature. It is the <strong>active default</strong>
 * provider, replacing the temporary {@link FixedDefaultAltitudeProvider}.
 *
 * <p><strong>Substitutability (E40).</strong> The seam interface is left intact: E40 will later
 * substitute its own {@link DefaultAltitudeProvider} (adaptive profile engine) behind the same
 * contract, and {@link FixedDefaultAltitudeProvider} remains available as a fixed test/bootstrap
 * fallback (it is no longer a Spring component, so it does not compete for injection).
 *
 * <p><strong>Policy, not engine.</strong> The returned altitude only parametrizes the projection;
 * the scheduling engine never consumes it.
 */
@Component
public class ProfileBackedAltitudeProvider implements DefaultAltitudeProvider {

    private final OrganizationProfileResolver profileResolver;

    /**
     * Constructs the profile-backed provider.
     *
     * @param profileResolver the EN18.10 organization-profile resolver
     */
    public ProfileBackedAltitudeProvider(final OrganizationProfileResolver profileResolver) {
        this.profileResolver = profileResolver;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code resolveProfile(tenantId).altitude()}. Propagates
     * {@link fr.pivot.pilotage.profile.TenantNotFoundException} for an unknown tenant (404
     * equivalent) — the projection never invents a default for a non-existent tenant.
     */
    @Override
    public Altitude defaultAltitude(final long tenantId) {
        return profileResolver.resolveProfile(tenantId).altitude();
    }
}
