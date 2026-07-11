package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.TemporalPrecision;

/**
 * Response DTO for a roadmap's fuzzy time scale (US22.3.2 — {@code GET/PUT .../roadmap/scale}).
 *
 * <p>The roadmap scale is a per-roadmap (per-project) view setting stored on
 * {@code pilotage.project.default_temporal_precision}. When that column is {@code null} the roadmap
 * has no explicit setting yet and the effective scale is derived from the tenant's default profile
 * (EN18.10) — consumed, never re-implemented, via
 * {@link fr.pivot.pilotage.profile.OrganizationProfileResolver}.
 *
 * @param scale     the effective scale actually applied to the roadmap (never {@code null}) — the
 *                  explicit per-roadmap setting when present, otherwise the profile-derived default
 * @param explicit  {@code true} when {@code scale} comes from an explicit per-roadmap setting;
 *                  {@code false} when it is inherited from the default profile (EN18.10). Lets the
 *                  frontend restitute "using the org default" state to a screen reader (A11y AC —
 *                  the backend surfaces the state; the widget is pivot-pilotage-ui's concern).
 */
public record RoadmapScaleResponse(TemporalPrecision scale, boolean explicit) {
}
