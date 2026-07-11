package fr.pivot.pilotage.schedule.event;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code pilotage.plan.v1} domain events (EN22.1c, frozen contract §d) on plan changes and
 * recalculations, with <strong>emission-side idempotence by {@code revision}</strong>.
 *
 * <p>Ordering &amp; idempotence (§d): events are ordered by {@code revision} per project; replaying a
 * {@code revision} already emitted for that project is a no-op ("rejouer une {@code revision ≤}
 * dernière vue = no-op"). {@link #publish} returns {@code true} when the envelope was published,
 * {@code false} when it was dropped as a stale/duplicate revision.
 *
 * <p><strong>Local bus today; inter-module bus deferred.</strong> Events are published through
 * Spring's {@link ApplicationEventPublisher} (in-process bus), as prescribed by CLAUDE.md (typed
 * events, no direct inter-module logic). The wiring to the PIVOT inter-module bus (ADR-006) depends
 * on {@code pivot-core-starter} (TODO-SETUP §5) and is a <em>documented gap</em> — not invented here.
 * A subscriber of a disabled module ignores the event silently (graceful degradation, §d): with the
 * local publisher this is naturally the case, since no such listener is registered.
 */
@Component
public class PlanEventPublisher {

    private final ApplicationEventPublisher delegate;

    /** Highest revision already emitted per project ref — the idempotence high-water mark. */
    private final Map<Long, Long> lastRevisionByProject = new ConcurrentHashMap<>();

    /**
     * Constructs the publisher over Spring's application event bus.
     *
     * @param delegate the Spring event publisher
     */
    public PlanEventPublisher(final ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    /**
     * Publishes an event if its {@code revision} is newer than the last one emitted for its project;
     * a replay of an already-seen revision (or an older one) is ignored (idempotence).
     *
     * @param envelope the event envelope
     * @return {@code true} if published, {@code false} if dropped as a duplicate/stale revision
     */
    public boolean publish(final PlanEventEnvelope<?> envelope) {
        final long projectRef = envelope.projectRef();
        final long revision = envelope.revision();
        final boolean[] emitted = {false};
        lastRevisionByProject.compute(projectRef, (ref, previous) -> {
            if (previous == null || revision > previous) {
                emitted[0] = true;
                return revision;
            }
            // Already seen this revision (or a newer one): replay is a no-op — keep the high-water mark.
            return previous;
        });
        if (emitted[0]) {
            delegate.publishEvent(envelope);
        }
        return emitted[0];
    }

    /**
     * Convenience builder + publish for the common case, stamping the current schema version and
     * emitter via {@link PlanEventEnvelope#of}.
     *
     * @param type       the event type
     * @param tenantId   the owning tenant id
     * @param projectRef the logical project reference
     * @param revision   the ordering/dedup revision
     * @param occurredAt when the change occurred (deterministic; never {@code now()} in tests)
     * @param payload    the payload
     * @return {@code true} if published, {@code false} if dropped as a duplicate/stale revision
     */
    public boolean publish(final PlanEventType type, final long tenantId, final long projectRef,
            final long revision, final Instant occurredAt, final PlanEventPayload payload) {
        return publish(PlanEventEnvelope.of(type, tenantId, projectRef, revision, occurredAt, payload));
    }
}
