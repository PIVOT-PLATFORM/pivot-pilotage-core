package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.TemporalPrecision;

/**
 * Request body of {@code PUT .../roadmap/scale} (US22.3.2) — sets the roadmap's fuzzy time scale
 * (mois / trimestre / semestre…), a per-roadmap view setting.
 *
 * <p>{@code scale} is required but deliberately not {@code @NotNull}: a {@code null}/unknown token
 * is rejected explicitly by {@code RoadmapService} ({@link InvalidRoadmapScaleException}) so the
 * 400 carries a caller-facing {@link ApiError} body (Spring's default bean-validation body omits
 * field messages unless {@code server.error.include-message} is set — not configured here; same
 * rationale as {@link CreateInitiativeRequest#laneId()}).
 *
 * @param scale the new roadmap scale grain; required
 */
public record UpdateRoadmapScaleRequest(TemporalPrecision scale) {
}
