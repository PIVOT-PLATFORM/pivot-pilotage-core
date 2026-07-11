package fr.pivot.pilotage.schedule.projection;

import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.NodeKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Pure-POJO tests for the EN22.1c projection DTOs and enums: {@link Altitude#parse} (including the
 * unknown-altitude 422 path), {@link Layout}, {@link Horizon}, the {@link PlanView} defensive copies
 * and the {@link FixedDefaultAltitudeProvider} EN18.10 seam.
 */
class ProjectionContractTest {

    private static final Instant AT = Instant.parse("2024-01-01T09:00:00Z");

    @Test
    void altitudeParsesCaseInsensitively() {
        assertThat(Altitude.parse("macro")).isEqualTo(Altitude.MACRO);
        assertThat(Altitude.parse(" DETAIL ")).isEqualTo(Altitude.DETAIL);
        assertThat(Altitude.parse("Detail")).isEqualTo(Altitude.DETAIL);
        assertThat(Altitude.values()).containsExactly(Altitude.MACRO, Altitude.DETAIL);
    }

    @Test
    void unknownAltitudeIsRejected() {
        assertThatExceptionOfType(UnknownAltitudeException.class)
                .isThrownBy(() -> Altitude.parse("INCONNUE"));
        assertThatExceptionOfType(UnknownAltitudeException.class)
                .isThrownBy(() -> Altitude.parse(null));
        assertThat(new UnknownAltitudeException("x")).hasMessageContaining("x");
    }

    @Test
    void layoutAndHorizonEnumsInstantiate() {
        assertThat(Layout.values()).containsExactly(Layout.TIMELINE, Layout.BUCKETS, Layout.GANTT);
        assertThat(Horizon.values()).containsExactly(Horizon.NOW, Horizon.NEXT, Horizon.LATER);
        assertThat(Layout.valueOf("GANTT")).isEqualTo(Layout.GANTT);
    }

    @Test
    void fixedDefaultAltitudeProviderIsMacroForAnyTenant() {
        final FixedDefaultAltitudeProvider provider = new FixedDefaultAltitudeProvider();
        assertThat(provider.defaultAltitude(1L)).isEqualTo(Altitude.MACRO);
        assertThat(provider.defaultAltitude(999L)).isEqualTo(Altitude.MACRO);
    }

    @Test
    void planViewTakesDefensiveCopies() {
        final List<PlanNodeView> nodes = new ArrayList<>();
        nodes.add(node(1L, Horizon.NOW));
        final List<DependencyView> deps = new ArrayList<>();
        deps.add(new DependencyView(9L, 1L, 2L, DependencyLinkType.FS, 0L));
        final Map<Long, SummaryAggregate> aggs = new HashMap<>();
        aggs.put(1L, new SummaryAggregate(1L, AT, AT, 0L, null, java.math.BigDecimal.ZERO, false, 0));
        final Map<Horizon, List<PlanNodeView>> buckets = new EnumMap<>(Horizon.class);
        buckets.put(Horizon.NOW, new ArrayList<>(nodes));

        final PlanView view = new PlanView(100L, Altitude.MACRO, Layout.BUCKETS, nodes, deps, aggs, buckets);

        // Mutating the source collections must not affect the view.
        nodes.clear();
        deps.clear();
        aggs.clear();
        buckets.get(Horizon.NOW).clear();
        assertThat(view.nodes()).hasSize(1);
        assertThat(view.dependencies()).hasSize(1);
        assertThat(view.aggregates()).containsKey(1L);
        assertThat(view.buckets().get(Horizon.NOW)).hasSize(1);
        assertThat(view.projectId()).isEqualTo(100L);
        assertThat(view.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(view.layout()).isEqualTo(Layout.BUCKETS);
    }

    @Test
    void planViewRejectsNullCollections() {
        assertThatNullPointerException().isThrownBy(() -> new PlanView(1L, Altitude.MACRO,
                Layout.TIMELINE, null, List.of(), Map.of(), Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new PlanView(1L, Altitude.MACRO,
                Layout.TIMELINE, List.of(), null, Map.of(), Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new PlanView(1L, Altitude.MACRO,
                Layout.TIMELINE, List.of(), List.of(), null, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new PlanView(1L, Altitude.MACRO,
                Layout.TIMELINE, List.of(), List.of(), Map.of(), null));
    }

    @Test
    void planNodeViewCarriesBothViewFields() {
        final PlanNodeView n = node(5L, null);
        assertThat(n.nodeId()).isEqualTo(5L);
        assertThat(n.nodeKind()).isEqualTo(NodeKind.LEAF);
        assertThat(n.sharedInRoadmap()).isFalse();
        assertThat(n.aggregated()).isFalse();
        assertThat(n.revision()).isEqualTo(3);
    }

    private static PlanNodeView node(final long id, final Horizon horizon) {
        return new PlanNodeView(id, null, "1", "T" + id, NodeKind.LEAF, false, horizon,
                null, null, AT, AT, Boolean.FALSE, false, 3);
    }
}
