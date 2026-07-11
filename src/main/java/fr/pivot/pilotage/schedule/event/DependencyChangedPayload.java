package fr.pivot.pilotage.schedule.event;

/**
 * Payload of {@link PlanEventType#DEPENDENCY_CHANGED} (EN22.1c, frozen contract §d) — emitted when a
 * dependency link is created, removed or retyped. {@code cycleRejected} signals a delta rejected for
 * introducing a cycle (the edge is not applied). Consumers: US22.4.3, EN22.2.
 *
 * @param edgeId        the dependency edge id
 * @param type          the link type token (FS / SS / FF / SF), or {@code null} on removal
 * @param lag           lag/lead in worked minutes
 * @param cycleRejected whether the change was rejected because it would introduce a cycle
 */
public record DependencyChangedPayload(
        long edgeId,
        String type,
        long lag,
        boolean cycleRejected) implements PlanEventPayload {
}
