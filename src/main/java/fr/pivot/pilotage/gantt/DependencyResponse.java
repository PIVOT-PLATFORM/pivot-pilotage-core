package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.DependencyLinkType;

/**
 * Response DTO for a typed task dependency (US22.4.3) — never the {@link
 * fr.pivot.pilotage.schedule.TaskDependency} JPA entity (CLAUDE.md §Standards: DTOs only).
 *
 * <p>Immutable record; the {@code lagMinutes} is the signed worked-minute offset (positive = lag /
 * retard, negative = lead / avance) applied on the successor task's calendar (decision D7).
 *
 * @param dependencyId      the dependency id
 * @param predecessorTaskId the predecessor task id
 * @param successorTaskId   the successor task id
 * @param linkType          the link type (FS / SS / FF / SF)
 * @param lagMinutes        the signed lag/lead in worked minutes
 */
public record DependencyResponse(
        long dependencyId,
        long predecessorTaskId,
        long successorTaskId,
        DependencyLinkType linkType,
        int lagMinutes) {
}
