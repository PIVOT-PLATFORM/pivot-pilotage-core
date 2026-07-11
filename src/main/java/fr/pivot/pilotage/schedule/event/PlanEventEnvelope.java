package fr.pivot.pilotage.schedule.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Versioned envelope of a {@code pilotage.plan.v1} domain event (EN22.1c, frozen contract §d).
 *
 * <p>Common envelope: {@code {schemaVersion, eventType, tenantId, projectRef, revision, occurredAt,
 * emittedBy, payload}}. Events are <strong>idempotent</strong> and <strong>ordered by {@link
 * #revision}</strong>: a consumer that has already seen a {@code revision} treats a replay as a
 * no-op ({@link PlanEventPublisher} enforces emission-side idempotence). The payload is a minimal
 * projection ({@link PlanEventPayload}) — never the internal {@code pilotage} schema nor a
 * cross-module FK (ADR-006).
 *
 * <p>Correlation is by logical identifiers only: {@link #tenantId} and {@link #projectRef} carry no
 * cross-module foreign key; a consumer of a disabled module ignores the event silently (graceful
 * degradation, §d).
 *
 * @param schemaVersion the envelope schema version (1 for {@code pilotage.plan.v1})
 * @param eventType     the event type
 * @param tenantId      the owning tenant's {@code public.tenants.id} (logical, no FK)
 * @param projectRef    the logical project reference (no cross-module FK)
 * @param revision      the monotonic revision that orders and deduplicates the event
 * @param occurredAt    when the change occurred (deterministic — never {@code now()} in tests)
 * @param emittedBy     the emitting module id (always {@code "pilotage"})
 * @param payload       the minimal-projection payload matching {@code eventType}
 * @param <P>           the payload type
 */
public record PlanEventEnvelope<P extends PlanEventPayload>(
        int schemaVersion,
        PlanEventType eventType,
        long tenantId,
        long projectRef,
        long revision,
        Instant occurredAt,
        String emittedBy,
        P payload) {

    /** The topic / schema version of this event contract. */
    public static final int SCHEMA_VERSION = 1;

    /** The emitting module id carried by every envelope. */
    public static final String EMITTED_BY = "pilotage";

    /**
     * Canonical constructor validating the non-null invariants of the envelope.
     *
     * @throws NullPointerException if {@code eventType}, {@code occurredAt}, {@code emittedBy} or
     *                              {@code payload} is {@code null}
     */
    public PlanEventEnvelope {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(emittedBy, "emittedBy");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Builds a {@code pilotage.plan.v1} envelope with the current schema version and emitter.
     *
     * @param eventType  the event type
     * @param tenantId   the owning tenant id
     * @param projectRef the logical project reference
     * @param revision   the ordering/dedup revision
     * @param occurredAt when the change occurred
     * @param payload    the payload
     * @param <P>        the payload type
     * @return a fully populated envelope
     */
    public static <P extends PlanEventPayload> PlanEventEnvelope<P> of(final PlanEventType eventType,
            final long tenantId, final long projectRef, final long revision, final Instant occurredAt,
            final P payload) {
        return new PlanEventEnvelope<>(SCHEMA_VERSION, eventType, tenantId, projectRef, revision,
                occurredAt, EMITTED_BY, payload);
    }
}
