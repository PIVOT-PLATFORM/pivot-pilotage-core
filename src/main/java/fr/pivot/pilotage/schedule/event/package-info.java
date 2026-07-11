/**
 * Event contract {@code pilotage.plan.v1} (EN22.1c, frozen contract §d) — versioned envelope
 * ({@link fr.pivot.pilotage.schedule.event.PlanEventEnvelope}), six domain event types
 * ({@link fr.pivot.pilotage.schedule.event.PlanEventType}) with minimal-projection payloads, and an
 * idempotent, revision-ordered publisher ({@link fr.pivot.pilotage.schedule.event.PlanEventPublisher})
 * over Spring's local application bus.
 *
 * <p><strong>Coverage matrix §(e) — consumer → contract element.</strong> Every key consumer of the
 * frozen contract finds its field / projection API / event here (verified by the traceability test):
 *
 * <table>
 *   <caption>Consumer to contract-element mapping (§e)</caption>
 *   <tr><th>Consumer</th><th>Field / model</th><th>Projection API</th><th>Event</th></tr>
 *   <tr><td>US22.3.2 fuzzy scale</td>
 *       <td>{@code temporal_precision}, {@code fuzzy_period_*}</td>
 *       <td>{@code altitude=MACRO, layout=TIMELINE}</td><td>— (view setting)</td></tr>
 *   <tr><td>US22.3.3 Now/Next/Later</td>
 *       <td>{@link fr.pivot.pilotage.schedule.Horizon} (nullable)</td>
 *       <td>{@code altitude=MACRO, layout=BUCKETS}</td>
 *       <td>{@link fr.pivot.pilotage.schedule.event.PlanEventType#HORIZON_CHANGED}</td></tr>
 *   <tr><td>US22.3.4 strategic milestones</td>
 *       <td>{@code MILESTONE} + {@code shared_in_roadmap}, stable id</td>
 *       <td>{@code MACRO} and {@code DETAIL}, same id</td>
 *       <td>{@link fr.pivot.pilotage.schedule.event.PlanEventType#MILESTONE_MOVED}</td></tr>
 *   <tr><td>US22.4.1a WBS model</td>
 *       <td>{@code wbs_code} (derived), {@code revision}</td>
 *       <td>{@code altitude=DETAIL, layout=GANTT}</td>
 *       <td>{@link fr.pivot.pilotage.schedule.event.PlanEventType#WBS_RESTRUCTURED}</td></tr>
 *   <tr><td>US22.4.1c summary rollup</td>
 *       <td>{@link fr.pivot.pilotage.schedule.projection.SummaryAggregate} (derived, never stored)</td>
 *       <td>detail plan</td>
 *       <td>{@link fr.pivot.pilotage.schedule.event.PlanEventType#NODE_SCHEDULE_CHANGED} ({@code agg})</td></tr>
 *   <tr><td>US22.4.3 typed dependencies</td>
 *       <td>{@code link_type} + {@code lag_minutes}</td>
 *       <td>edges in detail plan</td>
 *       <td>{@link fr.pivot.pilotage.schedule.event.PlanEventType#DEPENDENCY_CHANGED}</td></tr>
 *   <tr><td>US22.4.7 critical path</td>
 *       <td>{@code is_critical}, {@code free/total_slack_minutes} (derived)</td>
 *       <td>critical flag in plan</td>
 *       <td>{@link fr.pivot.pilotage.schedule.event.PlanEventType#PLAN_RECALCULATED}
 *           ({@code criticalPathChanged})</td></tr>
 *   <tr><td>EN22.2 perf / co-editing</td>
 *       <td>{@code revision}, {@code changedNodeIds}</td><td>—</td><td>all types</td></tr>
 *   <tr><td>E21 risks (overlay)</td>
 *       <td>{@code projectRef}, {@code nodeId}</td><td>plan (read)</td>
 *       <td>{@code PLAN_RECALCULATED}, {@code MILESTONE_MOVED}</td></tr>
 *   <tr><td>E23 portfolio</td>
 *       <td>aggregated milestones/dates/progress</td><td>macro plan multi-project</td>
 *       <td>{@code PLAN_RECALCULATED}, {@code MILESTONE_MOVED}, {@code HORIZON_CHANGED}</td></tr>
 * </table>
 */
package fr.pivot.pilotage.schedule.event;
