package fr.pivot.pilotage.schedule.projection;

import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.NodeKind;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable projection of a single graph node (EN22.1c, frozen contract §c) — the DTO returned by
 * {@link PlanProjectionService}, never a JPA entity (repo rule: entities are never exposed).
 *
 * <p>The <strong>same</strong> underlying node ({@link #nodeId} stable) is projected differently per
 * altitude: the macro view reads {@link #fuzzyPeriodStart}/{@link #fuzzyPeriodEnd} (and, for a
 * bucketed initiative, {@link #horizon}); the detail view reads {@link #startDate}/{@link
 * #finishDate}, {@link #wbsCode} and the engine-derived critical flag. A shared milestone
 * ({@code nodeKind == MILESTONE} and {@link #sharedInRoadmap}) is projected into <em>both</em>
 * views with the same {@link #nodeId} — the non-divergence invariant.
 *
 * @param nodeId          stable node id (identical across altitudes — never duplicated)
 * @param parentId        WBS parent node id, or {@code null} at the root
 * @param wbsCode         server-derived WBS code (detail view), or {@code null}
 * @param name            node name
 * @param nodeKind        kind of node (summary / leaf / milestone / recurring)
 * @param sharedInRoadmap whether the node is projected into the macro view
 * @param horizon         Now/Next/Later bucket (macro buckets layout), or {@code null}
 * @param fuzzyPeriodStart fuzzy period lower bound (macro view), or {@code null}
 * @param fuzzyPeriodEnd  fuzzy period upper bound (macro view), or {@code null}
 * @param startDate       precise start (detail view), or {@code null}
 * @param finishDate      precise finish (detail view), or {@code null}
 * @param critical        engine-derived critical-path flag (detail view), or {@code null}
 * @param aggregated      whether {@code startDate}/{@code finishDate} are a summary rollup (derived,
 *                        never persisted twice)
 * @param revision        monotonic revision — optimistic co-editing lock and event ordering
 */
public record PlanNodeView(
        long nodeId,
        Long parentId,
        String wbsCode,
        String name,
        NodeKind nodeKind,
        boolean sharedInRoadmap,
        Horizon horizon,
        LocalDate fuzzyPeriodStart,
        LocalDate fuzzyPeriodEnd,
        Instant startDate,
        Instant finishDate,
        Boolean critical,
        boolean aggregated,
        int revision) {
}
