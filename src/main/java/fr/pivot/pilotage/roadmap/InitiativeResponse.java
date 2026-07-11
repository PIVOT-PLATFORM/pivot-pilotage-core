package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.time.LocalDate;

/**
 * Response DTO for a roadmap-rapide initiative (US22.3.1) — never the JPA entity directly
 * (CLAUDE.md §Standards). An initiative <strong>is</strong> a {@link Task} (leaf, shared in the
 * roadmap, assigned to a lane); this DTO only exposes the subset of its fields relevant to the
 * roadmap-rapide altitude — approximate period, never the precise Gantt dates.
 *
 * <p><strong>US22.3.2 — fuzzy scale.</strong> {@link #fuzzyPeriodStart}/{@link #fuzzyPeriodEnd} are
 * the raw stored period (untouched by the view scale — so a scale change never destroys them, the
 * error AC). {@link #periodBounds} is the same period <em>snapped</em> to the current roadmap scale
 * ({@link RoadmapScale#snap}) — the bounds a bar actually aligns to ("les barres s'alignent sur des
 * bornes de période"). Both {@code null} when the initiative has no period at all ("aucune date
 * exacte n'est imposée").
 *
 * <p><strong>US22.3.3 — Now/Next/Later.</strong> {@link #horizon} is the initiative's bucket, or
 * {@code null} if never bucketised (surfaced in {@code HorizonViewResponse.unbucketed}).
 *
 * @param id                the underlying task id
 * @param laneId            the lane this initiative is posed on
 * @param name              the initiative name
 * @param fuzzyPeriodStart  the raw approximate period lower bound, or {@code null} if not yet placed
 * @param fuzzyPeriodEnd    the raw approximate period upper bound, or {@code null} if not yet placed
 * @param periodBounds      the period snapped to the roadmap scale (US22.3.2) — the rendered bar
 *                          bounds; both ends {@code null} when the raw period is absent
 * @param horizon           the Now/Next/Later bucket (US22.3.3), or {@code null} if not bucketised
 * @param temporalPrecision the effective altitude grain (never {@code DAY} for a roadmap-rapide
 *                          initiative — the fuzzy period is always authoritative here)
 * @param revision          monotonic revision counter (optimistic co-editing lock)
 */
public record InitiativeResponse(
        long id,
        long laneId,
        String name,
        LocalDate fuzzyPeriodStart,
        LocalDate fuzzyPeriodEnd,
        PeriodBounds periodBounds,
        Horizon horizon,
        TemporalPrecision temporalPrecision,
        int revision) {

    /**
     * Maps a {@link Task} entity to its response DTO, snapping its period to the given roadmap
     * scale (US22.3.2).
     *
     * @param task  the persisted task (must carry a non-null {@code laneId})
     * @param scale the effective roadmap scale the bar bounds are snapped to
     * @return the mapped response
     */
    static InitiativeResponse from(final Task task, final TemporalPrecision scale) {
        return new InitiativeResponse(task.getId(), task.getLaneId(), task.getName(),
                task.getFuzzyPeriodStart(), task.getFuzzyPeriodEnd(),
                RoadmapScale.snap(scale, task.getFuzzyPeriodStart(), task.getFuzzyPeriodEnd()),
                task.getHorizon(), task.getTemporalPrecision(),
                task.getRevision() == null ? 0 : task.getRevision());
    }

    /**
     * Maps a {@link Task} entity to its response DTO, snapping its period to the task's own
     * effective precision (used on the single-initiative write paths, where no separate roadmap
     * scale is being projected).
     *
     * @param task the persisted task (must carry a non-null {@code laneId})
     * @return the mapped response
     */
    static InitiativeResponse from(final Task task) {
        return from(task, task.getTemporalPrecision());
    }
}
