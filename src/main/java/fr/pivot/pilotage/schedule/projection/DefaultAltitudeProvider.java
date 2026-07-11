package fr.pivot.pilotage.schedule.projection;

/**
 * Seam for the tenant's default view altitude (frozen contract §c: "l'altitude par défaut est
 * fournie par {@code resolveProfile(tenant).altitude}").
 *
 * <p><strong>Dependency gap — EN18.10.</strong> The real profile-resolution service (EN18.10, the
 * "altitude par défaut / curseur de vue" policy, substitutable via E40) is <em>not yet
 * implemented</em>. This interface is the substitutable seam it will replace: EN18.10 provides a
 * bean implementing it that reads {@code resolveProfile(tenant).altitude}, without changing the
 * projection signature. Until then {@link FixedDefaultAltitudeProvider} supplies a fixed macro
 * default.
 *
 * <p>The default is consulted <strong>only</strong> when a projection request does not carry an
 * explicit altitude — {@link PlanProjectionService#project} always takes the altitude as an explicit
 * argument, so this provider never overrides an explicit choice.
 */
public interface DefaultAltitudeProvider {

    /**
     * Returns the default view altitude for a tenant (the render cursor's opening notch).
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the default {@link Altitude} for that tenant
     */
    Altitude defaultAltitude(long tenantId);
}
