package fr.pivot.pilotage.schedule.event;

/**
 * The six domain event types of the {@code pilotage.plan.v1} contract (EN22.1c, frozen contract
 * §d). Each is a minimal projection over the bus — never the internal {@code pilotage} schema and
 * never a cross-module FK (ADR-006).
 */
public enum PlanEventType {

    /** End of an incremental recalculation (EN22.2, E23, E21). */
    PLAN_RECALCULATED,

    /** A milestone's date changed — propagated to macro and detail (US22.3.4, E23, E24). */
    MILESTONE_MOVED,

    /** A node's dates/duration changed (leaf or aggregated summary) (EN22.2, US22.4.1c, E26). */
    NODE_SCHEDULE_CHANGED,

    /** A dependency link was created/removed/retyped (US22.4.3, EN22.2). */
    DEPENDENCY_CHANGED,

    /** An initiative changed Now/Next/Later bucket (US22.3.3, E23). */
    HORIZON_CHANGED,

    /** The WBS hierarchy was recomputed server-side (US22.4.1a/b, EN22.2). */
    WBS_RESTRUCTURED
}
