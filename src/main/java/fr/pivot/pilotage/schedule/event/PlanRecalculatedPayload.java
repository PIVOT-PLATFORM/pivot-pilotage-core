package fr.pivot.pilotage.schedule.event;

import java.util.List;
import java.util.Objects;

/**
 * Payload of {@link PlanEventType#PLAN_RECALCULATED} (EN22.1c, frozen contract §d) — emitted at the
 * end of an incremental recalculation. Consumers: EN22.2, E23, E21.
 *
 * @param changedNodeIds     the recalculated node ids (a diff, not the whole plan)
 * @param criticalPathChanged whether the critical path changed
 */
public record PlanRecalculatedPayload(
        List<Long> changedNodeIds,
        boolean criticalPathChanged) implements PlanEventPayload {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the node-id list.
     *
     * @throws NullPointerException if {@code changedNodeIds} is {@code null}
     */
    public PlanRecalculatedPayload {
        changedNodeIds = List.copyOf(Objects.requireNonNull(changedNodeIds, "changedNodeIds"));
    }
}
