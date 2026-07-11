package fr.pivot.pilotage.schedule.event;

/**
 * Payload of {@link PlanEventType#HORIZON_CHANGED} (EN22.1c, frozen contract §d) — emitted when an
 * initiative changes Now/Next/Later bucket. Consumers: US22.3.3, E23.
 *
 * @param nodeId     the initiative node id
 * @param oldHorizon the previous horizon token ({@code NOW}/{@code NEXT}/{@code LATER}), or {@code
 *                   null} if previously unbucketised
 * @param newHorizon the new horizon token, or {@code null} if unbucketised
 */
public record HorizonChangedPayload(
        long nodeId,
        String oldHorizon,
        String newHorizon) implements PlanEventPayload {
}
