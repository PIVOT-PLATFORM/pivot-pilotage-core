package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.time.LocalDate;

/**
 * Response DTO for a strategic milestone (US22.3.4) — never the JPA entity directly (CLAUDE.md
 * §Standards). A milestone <strong>is</strong> a {@link Task} (node_kind {@code MILESTONE}, shared
 * in the roadmap, optionally assigned to a lane) with a single {@link #date}, never a period —
 * distinct from {@link InitiativeResponse}, which carries an approximate period and never a
 * milestone's exact single date.
 *
 * <p><strong>Same object, no transformation, both views (AC "visible on the roadmap AND the
 * Gantt").</strong> {@link RoadmapService} writes {@code date} onto <em>both</em> temporal
 * representations carried by the same {@code task} row: the fuzzy period bounds
 * ({@code fuzzy_period_start}={@code fuzzy_period_end}={@code date}, read by this DTO and by the
 * roadmap-rapide macro view) and the precise Gantt bounds ({@code start_date}=
 * {@code finish_date}={@code date} at UTC midnight, read directly by a future Gantt consumer) — so
 * either view resolves the identical date without any service-side conversion at read time.
 *
 * @param id                the underlying task id
 * @param laneId            the lane this milestone is pinned to, or {@code null} for a
 *                          project-wide marker not tied to any lane
 * @param name              the milestone name
 * @param date              the milestone's single date
 * @param temporalPrecision the effective altitude grain (always {@link TemporalPrecision#DAY} for
 *                          a milestone — a single exact date, never a fuzzy period)
 * @param revision          monotonic revision counter (optimistic co-editing lock)
 */
public record MilestoneResponse(
        long id,
        Long laneId,
        String name,
        LocalDate date,
        TemporalPrecision temporalPrecision,
        int revision) {

    /**
     * Maps a {@link Task} entity to its response DTO.
     *
     * @param task the persisted task (must carry {@code node_kind=MILESTONE})
     * @return the mapped response
     */
    static MilestoneResponse from(final Task task) {
        return new MilestoneResponse(task.getId(), task.getLaneId(), task.getName(),
                task.getFuzzyPeriodStart(), task.getTemporalPrecision(),
                task.getRevision() == null ? 0 : task.getRevision());
    }
}
