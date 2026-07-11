package fr.pivot.pilotage.roadmap;

import java.time.LocalDate;

/**
 * Snapped period bounds of a roadmap-rapide bar at the current view scale (US22.3.2). These are the
 * <em>rendered</em> bounds a bar aligns to (the AC "les barres s'alignent sur des bornes de
 * période") — the first/last day of the containing period at the roadmap's
 * {@link fr.pivot.pilotage.schedule.TemporalPrecision scale}, computed from the stored fuzzy period
 * by {@link RoadmapScale#snap}.
 *
 * <p>A {@code null} bound means the initiative carries no precise date at that end (AC "aucune date
 * exacte n'est imposée") — snapping never fabricates one.
 *
 * @param start the snapped period lower bound, or {@code null} if the initiative has no start
 * @param end   the snapped period upper bound, or {@code null} if the initiative has no end
 */
public record PeriodBounds(LocalDate start, LocalDate end) {
}
