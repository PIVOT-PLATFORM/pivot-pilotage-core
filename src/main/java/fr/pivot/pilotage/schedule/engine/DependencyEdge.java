package fr.pivot.pilotage.schedule.engine;

import java.util.Objects;

/**
 * Immutable dependency edge consumed by the engine (EN22.1b).
 *
 * @param edgeId       stable edge id (diagnostics / diff correlation); may be 0 if unused
 * @param predecessorId predecessor task id
 * @param successorId  successor task id
 * @param linkType     FS / SS / FF / SF
 * @param lagMinutes   lag ({@code >0}) or lead ({@code <0}) in worked minutes; snapped to hour grain
 */
public record DependencyEdge(
        long edgeId,
        long predecessorId,
        long successorId,
        LinkType linkType,
        long lagMinutes) {

    /**
     * Canonical constructor validating the edge.
     *
     * @throws NullPointerException     if {@code linkType} is null
     * @throws IllegalArgumentException if predecessor equals successor
     */
    public DependencyEdge {
        Objects.requireNonNull(linkType, "linkType");
        if (predecessorId == successorId) {
            throw new IllegalArgumentException("dependency cannot link a task to itself: " + predecessorId);
        }
    }

    /**
     * Convenience factory for a finish-to-start edge with no lag.
     *
     * @param predecessorId predecessor task id
     * @param successorId   successor task id
     * @return the FS edge
     */
    public static DependencyEdge fs(final long predecessorId, final long successorId) {
        return new DependencyEdge(0L, predecessorId, successorId, LinkType.FS, 0L);
    }
}
