package fr.pivot.pilotage.schedule.projection;

import fr.pivot.pilotage.schedule.DependencyLinkType;

/**
 * Immutable projection of a dependency edge (EN22.1c, frozen contract §c — Gantt dependencies,
 * US22.4.3). Returned only in the detail (Gantt) projection; the macro view carries no edges.
 *
 * @param edgeId          dependency edge id
 * @param predecessorId   predecessor node id
 * @param successorId     successor node id
 * @param linkType        link type (FS / SS / FF / SF)
 * @param lagMinutes      lag ({@code >0}) or lead ({@code <0}) in worked minutes
 */
public record DependencyView(
        long edgeId,
        long predecessorId,
        long successorId,
        DependencyLinkType linkType,
        long lagMinutes) {
}
