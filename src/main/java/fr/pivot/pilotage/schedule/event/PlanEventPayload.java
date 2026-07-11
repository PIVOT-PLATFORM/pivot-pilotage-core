package fr.pivot.pilotage.schedule.event;

/**
 * Marker for a {@code pilotage.plan.v1} event payload (EN22.1c, frozen contract §d). Every payload
 * is a <strong>minimal projection</strong> — logical identifiers, dates, types and deep-link keys
 * only, never the internal {@code pilotage} schema nor a cross-module FK (ADR-006 / security AC).
 */
public sealed interface PlanEventPayload
        permits PlanRecalculatedPayload, MilestoneMovedPayload, NodeScheduleChangedPayload,
        DependencyChangedPayload, HorizonChangedPayload, WbsRestructuredPayload {
}
