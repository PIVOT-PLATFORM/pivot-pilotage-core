package fr.pivot.pilotage.consolidation;

import java.time.LocalDate;

/**
 * Immutable view of a strategic milestone surfaced at the <strong>Application</strong> level
 * (EN18.9). It is the union, across all of an application's projects, of the milestones flagged
 * {@code shared_in_roadmap} in the temporal graph (EN22.1) — the pilotage-owned "roadmap" markers a
 * stakeholder tracks a product by, across its successive project/versions.
 *
 * <p>Carries the owning {@code projectId} so every application-level milestone stays traceable back
 * to exactly one project (and, transitively, one application — the {@code project → application}
 * traceability guaranteed by the EN18.1 FK).
 *
 * @param nodeId           the temporal-graph node id of the milestone (stable, never duplicated)
 * @param projectId        the owning project id (traceability {@code milestone → project})
 * @param name             the milestone name
 * @param fuzzyPeriodStart roadmap-altitude lower bound, or {@code null} if undated
 * @param fuzzyPeriodEnd   roadmap-altitude upper bound, or {@code null} if undated
 */
public record ApplicationMilestone(
        long nodeId,
        long projectId,
        String name,
        LocalDate fuzzyPeriodStart,
        LocalDate fuzzyPeriodEnd) {
}
