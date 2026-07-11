package fr.pivot.pilotage.schedule.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PlanEventPublisher} (EN22.1c, frozen contract §d): events are published on a
 * plan change and are <strong>idempotent by {@code revision}</strong> — replaying an already-seen
 * (or older) revision for the same project is a no-op. Timestamps are anchored in the past.
 */
@ExtendWith(MockitoExtension.class)
class PlanEventPublisherTest {

    private static final long TENANT = 7L;
    private static final long PROJECT = 100L;
    private static final Instant AT = Instant.parse("2024-01-01T09:00:00Z");

    @Mock private ApplicationEventPublisher delegate;

    private PlanEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PlanEventPublisher(delegate);
    }

    @Test
    void publishesOnChange() {
        final boolean emitted = publisher.publish(PlanEventType.PLAN_RECALCULATED, TENANT, PROJECT,
                1L, AT, new PlanRecalculatedPayload(List.of(1L, 2L), true));
        assertThat(emitted).isTrue();

        final ArgumentCaptor<PlanEventEnvelope<?>> captor = envelopeCaptor();
        verify(delegate).publishEvent(captor.capture());
        final PlanEventEnvelope<?> env = captor.getValue();
        assertThat(env.schemaVersion()).isEqualTo(PlanEventEnvelope.SCHEMA_VERSION);
        assertThat(env.emittedBy()).isEqualTo("pilotage");
        assertThat(env.eventType()).isEqualTo(PlanEventType.PLAN_RECALCULATED);
        assertThat(env.revision()).isEqualTo(1L);
        assertThat(env.projectRef()).isEqualTo(PROJECT);
    }

    @Test
    void sameRevisionTwice_isIgnored() {
        final PlanEventEnvelope<MilestoneMovedPayload> env = PlanEventEnvelope.of(
                PlanEventType.MILESTONE_MOVED, TENANT, PROJECT, 42L, AT,
                new MilestoneMovedPayload(1L, AT, AT.plusSeconds(3600), List.of("macro", "detail")));

        assertThat(publisher.publish(env)).isTrue();  // first delivery
        assertThat(publisher.publish(env)).isFalse(); // replay of revision 42 → no-op

        verify(delegate, times(1)).publishEvent(env); // published exactly once
    }

    @Test
    void olderRevision_isIgnored_newerIsPublished() {
        assertThat(publisher.publish(PlanEventType.NODE_SCHEDULE_CHANGED, TENANT, PROJECT, 5L, AT,
                new NodeScheduleChangedPayload(1L, false, AT, AT))).isTrue();
        // A stale revision (≤ last seen) is dropped.
        assertThat(publisher.publish(PlanEventType.NODE_SCHEDULE_CHANGED, TENANT, PROJECT, 3L, AT,
                new NodeScheduleChangedPayload(1L, false, AT, AT))).isFalse();
        // A newer revision is published.
        assertThat(publisher.publish(PlanEventType.NODE_SCHEDULE_CHANGED, TENANT, PROJECT, 6L, AT,
                new NodeScheduleChangedPayload(1L, false, AT, AT))).isTrue();

        verify(delegate, times(2)).publishEvent(org.mockito.ArgumentMatchers.any(PlanEventEnvelope.class));
    }

    @Test
    void revisionsAreTrackedPerProject() {
        assertThat(publisher.publish(PlanEventType.HORIZON_CHANGED, TENANT, PROJECT, 10L, AT,
                new HorizonChangedPayload(1L, "NOW", "NEXT"))).isTrue();
        // Same revision but a different project → not a duplicate.
        assertThat(publisher.publish(PlanEventType.HORIZON_CHANGED, TENANT, PROJECT + 1, 10L, AT,
                new HorizonChangedPayload(2L, "NEXT", "LATER"))).isTrue();
        verify(delegate, times(2)).publishEvent(org.mockito.ArgumentMatchers.any(PlanEventEnvelope.class));
    }

    @Test
    void dependencyAndWbsEvents_publish() {
        assertThat(publisher.publish(PlanEventType.DEPENDENCY_CHANGED, TENANT, PROJECT, 1L, AT,
                new DependencyChangedPayload(9L, "FS", 0L, false))).isTrue();
        assertThat(publisher.publish(PlanEventType.WBS_RESTRUCTURED, TENANT, PROJECT, 2L, AT,
                new WbsRestructuredPayload(List.of(1L, 2L, 3L)))).isTrue();
        verify(delegate, times(2)).publishEvent(org.mockito.ArgumentMatchers.any(PlanEventEnvelope.class));
    }

    @Test
    void noListenerRegistered_doesNotFail() {
        // Graceful degradation: with the local bus and no subscriber, publishing is a no-op sink.
        verify(delegate, never()).publishEvent(org.mockito.ArgumentMatchers.any(PlanEventEnvelope.class));
        assertThat(publisher.publish(PlanEventType.PLAN_RECALCULATED, TENANT, PROJECT, 1L, AT,
                new PlanRecalculatedPayload(List.of(), false))).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<PlanEventEnvelope<?>> envelopeCaptor() {
        return ArgumentCaptor.forClass(PlanEventEnvelope.class);
    }
}
