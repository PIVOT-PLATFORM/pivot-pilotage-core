/**
 * WBS (Work Breakdown Structure) of the detailed Gantt (F22.4 — US22.4.1a/b/c).
 *
 * <p>Exposes the tree operations on a project's temporal graph: creating a task under a parent,
 * indent/outdent, reordering and reading the ordered tree with server-derived WBS codes, summary
 * aggregates and ARIA attributes. A WBS node is a plain {@code fr.pivot.pilotage.schedule.Task}
 * (never a separate entity); the numbering is re-derived by the EN22.1b engine
 * ({@code SchedulingService}) and the summary rollups by the EN22.1c projection
 * ({@code PlanProjectionService}) — this package reuses both rather than recomputing them.
 *
 * <p>Follows the {@code fr.pivot.pilotage.roadmap} REST pattern: a thin controller, a service
 * holding the logic, DTOs (never entities), tenant/team explicit arguments (pre-starter gap), a
 * fail-closed edit policy (403), non-disclosing 404 on cross-tenant/team access, and a
 * package-scoped exception handler.
 */
package fr.pivot.pilotage.gantt;
