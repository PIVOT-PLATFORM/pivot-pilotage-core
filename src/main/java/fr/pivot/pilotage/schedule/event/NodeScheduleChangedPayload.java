package fr.pivot.pilotage.schedule.event;

import java.time.Instant;

/**
 * Payload of {@link PlanEventType#NODE_SCHEDULE_CHANGED} (EN22.1c, frozen contract §d) — emitted
 * when a node's dates/duration change (a leaf or an aggregated summary). Consumers: EN22.2,
 * US22.4.1c, E26.
 *
 * @param nodeId   the node id
 * @param agg      whether the node is an aggregated summary (rollup) rather than a leaf
 * @param newStart the new start, or {@code null}
 * @param newEnd   the new end, or {@code null}
 */
public record NodeScheduleChangedPayload(
        long nodeId,
        boolean agg,
        Instant newStart,
        Instant newEnd) implements PlanEventPayload {
}
