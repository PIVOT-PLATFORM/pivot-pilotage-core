package fr.pivot.pilotage.schedule.projection;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of a projection (EN22.1c, frozen contract §c) — two views are two projections of
 * the <strong>same</strong> persisted graph, not two stores. A {@link Altitude#MACRO} view carries
 * fuzzy-period nodes, shared milestones and summary rollups (and, in {@link Layout#BUCKETS}, the
 * {@code horizon} grouping); a {@link Altitude#DETAIL} view carries the precise-date WBS tree, its
 * dependency edges and the engine-derived critical path.
 *
 * <p>The <strong>shared milestone</strong> ({@code node_kind=MILESTONE}, {@code shared_in_roadmap})
 * appears in both a macro and a detail projection with the <em>same</em> node id — the testable
 * non-divergence invariant.
 *
 * @param projectId    the projected project id
 * @param altitude     the effective altitude of this projection (explicit or tenant default)
 * @param layout       the rendering layout
 * @param nodes        the projected nodes (immutable)
 * @param dependencies dependency edges (empty for a macro view)
 * @param aggregates   summary rollups keyed by summary node id (derived, never persisted twice)
 * @param buckets      Now/Next/Later grouping (non-empty only for {@link Layout#BUCKETS})
 */
public record PlanView(
        long projectId,
        Altitude altitude,
        Layout layout,
        List<PlanNodeView> nodes,
        List<DependencyView> dependencies,
        Map<Long, SummaryAggregate> aggregates,
        Map<fr.pivot.pilotage.schedule.Horizon, List<PlanNodeView>> buckets) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies of every collection so the view is
     * fully immutable (SpotBugs {@code EI_EXPOSE_REP}).
     *
     * @throws NullPointerException if any collection is {@code null}
     */
    public PlanView {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        aggregates = Map.copyOf(Objects.requireNonNull(aggregates, "aggregates"));
        final Map<fr.pivot.pilotage.schedule.Horizon, List<PlanNodeView>> copied =
                new java.util.EnumMap<>(fr.pivot.pilotage.schedule.Horizon.class);
        Objects.requireNonNull(buckets, "buckets")
                .forEach((k, v) -> copied.put(k, List.copyOf(v)));
        buckets = java.util.Collections.unmodifiableMap(copied);
    }
}
