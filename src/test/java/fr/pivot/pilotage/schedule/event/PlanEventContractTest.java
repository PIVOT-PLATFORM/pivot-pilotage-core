package fr.pivot.pilotage.schedule.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Pure-POJO tests for the {@code pilotage.plan.v1} event contract (EN22.1c, frozen contract §d):
 * the six payload records, their defensive copies and null-validation, the versioned envelope, and
 * the {@link PlanEventType} enumeration (coverage matrix §e — all six types present).
 */
class PlanEventContractTest {

    private static final Instant AT = Instant.parse("2024-01-01T09:00:00Z");

    @Test
    void allSixEventTypesArePresent() {
        assertThat(PlanEventType.values()).containsExactlyInAnyOrder(
                PlanEventType.PLAN_RECALCULATED,
                PlanEventType.MILESTONE_MOVED,
                PlanEventType.NODE_SCHEDULE_CHANGED,
                PlanEventType.DEPENDENCY_CHANGED,
                PlanEventType.HORIZON_CHANGED,
                PlanEventType.WBS_RESTRUCTURED);
        assertThat(PlanEventType.valueOf("PLAN_RECALCULATED")).isEqualTo(PlanEventType.PLAN_RECALCULATED);
    }

    @Test
    void envelopeCarriesVersionAndEmitter() {
        final PlanEventEnvelope<PlanRecalculatedPayload> env = PlanEventEnvelope.of(
                PlanEventType.PLAN_RECALCULATED, 7L, 100L, 3L, AT,
                new PlanRecalculatedPayload(List.of(1L), true));
        assertThat(env.schemaVersion()).isEqualTo(1);
        assertThat(env.emittedBy()).isEqualTo("pilotage");
        assertThat(env.tenantId()).isEqualTo(7L);
        assertThat(env.projectRef()).isEqualTo(100L);
        assertThat(env.revision()).isEqualTo(3L);
        assertThat(env.occurredAt()).isEqualTo(AT);
        assertThat(env.payload().criticalPathChanged()).isTrue();
    }

    @Test
    void envelopeRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new PlanEventEnvelope<>(
                1, null, 7L, 100L, 1L, AT, "pilotage", new WbsRestructuredPayload(List.of())));
        assertThatNullPointerException().isThrownBy(() -> new PlanEventEnvelope<>(
                1, PlanEventType.WBS_RESTRUCTURED, 7L, 100L, 1L, null, "pilotage",
                new WbsRestructuredPayload(List.of())));
        assertThatNullPointerException().isThrownBy(() -> new PlanEventEnvelope<>(
                1, PlanEventType.WBS_RESTRUCTURED, 7L, 100L, 1L, AT, null,
                new WbsRestructuredPayload(List.of())));
        assertThatNullPointerException().isThrownBy(() -> new PlanEventEnvelope<>(
                1, PlanEventType.WBS_RESTRUCTURED, 7L, 100L, 1L, AT, "pilotage", null));
    }

    @Test
    void planRecalculatedPayloadCopiesDefensively() {
        final List<Long> ids = new ArrayList<>(List.of(1L, 2L));
        final PlanRecalculatedPayload p = new PlanRecalculatedPayload(ids, false);
        ids.add(3L); // must not leak into the payload
        assertThat(p.changedNodeIds()).containsExactly(1L, 2L);
        assertThat(p.criticalPathChanged()).isFalse();
        assertThatNullPointerException().isThrownBy(() -> new PlanRecalculatedPayload(null, false));
    }

    @Test
    void milestoneMovedPayloadCopiesDefensively() {
        final List<String> alt = new ArrayList<>(List.of("macro", "detail"));
        final MilestoneMovedPayload p = new MilestoneMovedPayload(1L, AT, AT.plusSeconds(60), alt);
        alt.clear();
        assertThat(p.altitudes()).containsExactly("macro", "detail");
        assertThat(p.milestoneId()).isEqualTo(1L);
        assertThat(p.oldDate()).isEqualTo(AT);
        assertThat(p.newDate()).isEqualTo(AT.plusSeconds(60));
        assertThatNullPointerException()
                .isThrownBy(() -> new MilestoneMovedPayload(1L, AT, AT, null));
    }

    @Test
    void nodeScheduleChangedPayloadValues() {
        final NodeScheduleChangedPayload p = new NodeScheduleChangedPayload(5L, true, AT, AT.plusSeconds(3600));
        assertThat(p.nodeId()).isEqualTo(5L);
        assertThat(p.agg()).isTrue();
        assertThat(p.newStart()).isEqualTo(AT);
        assertThat(p.newEnd()).isEqualTo(AT.plusSeconds(3600));
    }

    @Test
    void dependencyChangedPayloadValues() {
        final DependencyChangedPayload p = new DependencyChangedPayload(9L, "FS", 30L, true);
        assertThat(p.edgeId()).isEqualTo(9L);
        assertThat(p.type()).isEqualTo("FS");
        assertThat(p.lag()).isEqualTo(30L);
        assertThat(p.cycleRejected()).isTrue();
    }

    @Test
    void horizonChangedPayloadValues() {
        final HorizonChangedPayload p = new HorizonChangedPayload(3L, "NOW", "LATER");
        assertThat(p.nodeId()).isEqualTo(3L);
        assertThat(p.oldHorizon()).isEqualTo("NOW");
        assertThat(p.newHorizon()).isEqualTo("LATER");
    }

    @Test
    void wbsRestructuredPayloadCopiesDefensively() {
        final List<Long> ids = new ArrayList<>(List.of(1L, 2L));
        final WbsRestructuredPayload p = new WbsRestructuredPayload(ids);
        ids.add(9L);
        assertThat(p.affectedNodeIds()).containsExactly(1L, 2L);
        assertThatNullPointerException().isThrownBy(() -> new WbsRestructuredPayload(null));
    }
}
