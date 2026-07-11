package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.time.LocalDate;

/**
 * Response DTO for a roadmap-rapide initiative (US22.3.1) — never the JPA entity directly
 * (CLAUDE.md §Standards). An initiative <strong>is</strong> a {@link Task} (leaf, shared in the
 * roadmap, assigned to a lane); this DTO only exposes the subset of its fields relevant to the
 * roadmap-rapide altitude — approximate period, never the precise Gantt dates.
 *
 * @param id                the underlying task id
 * @param laneId            the lane this initiative is posed on
 * @param name              the initiative name
 * @param fuzzyPeriodStart  the approximate period lower bound, or {@code null} if not yet placed
 * @param fuzzyPeriodEnd    the approximate period upper bound, or {@code null} if not yet placed
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
        TemporalPrecision temporalPrecision,
        int revision) {

    /**
     * Maps a {@link Task} entity to its response DTO.
     *
     * @param task the persisted task (must carry a non-null {@code laneId})
     * @return the mapped response
     */
    static InitiativeResponse from(final Task task) {
        return new InitiativeResponse(task.getId(), task.getLaneId(), task.getName(),
                task.getFuzzyPeriodStart(), task.getFuzzyPeriodEnd(), task.getTemporalPrecision(),
                task.getRevision() == null ? 0 : task.getRevision());
    }
}
