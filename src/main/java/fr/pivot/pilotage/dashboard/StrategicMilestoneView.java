package fr.pivot.pilotage.dashboard;

import java.time.LocalDate;

/**
 * Response view of one strategic milestone rendered by a
 * {@link DashboardWidgetType#STRATEGIC_MILESTONES} widget (US23.2.2) — a thin projection of EN18.9's
 * {@code fr.pivot.pilotage.consolidation.ApplicationMilestone}, never the consolidation type
 * directly (this module's own response shape, decoupled from EN18.9's internal representation).
 *
 * @param nodeId      the temporal-graph node id of the milestone
 * @param projectId   the owning project id
 * @param name        the milestone name
 * @param periodStart roadmap-altitude lower bound, or {@code null} if undated
 * @param periodEnd   roadmap-altitude upper bound, or {@code null} if undated
 */
public record StrategicMilestoneView(long nodeId, long projectId, String name, LocalDate periodStart,
        LocalDate periodEnd) {
}
