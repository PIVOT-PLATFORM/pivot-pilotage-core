package fr.pivot.pilotage.schedule.event;

import java.util.List;
import java.util.Objects;

/**
 * Payload of {@link PlanEventType#WBS_RESTRUCTURED} (EN22.1c, frozen contract §d) — emitted when the
 * WBS hierarchy is recomputed server-side. Consumers: US22.4.1a/b, EN22.2.
 *
 * @param affectedNodeIds the nodes whose WBS code changed
 */
public record WbsRestructuredPayload(
        List<Long> affectedNodeIds) implements PlanEventPayload {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the node-id list.
     *
     * @throws NullPointerException if {@code affectedNodeIds} is {@code null}
     */
    public WbsRestructuredPayload {
        affectedNodeIds = List.copyOf(Objects.requireNonNull(affectedNodeIds, "affectedNodeIds"));
    }
}
