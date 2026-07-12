package fr.pivot.pilotage.portfolio;

/**
 * Immutable, portfolio-altitude view of a single {@code pilotage.phase} row (US23.2.1) — the
 * "phases" dimension of the consolidated view (AC: "phases... sont consolidés"). Carries only the
 * display identity (id, name, position); the phase's own detail (backing summary task, if any) stays
 * on the fiche projet (module Roadmap, E22) the drill-down navigates to — no duplication of content.
 *
 * @param phaseId  the {@code pilotage.phase.id}
 * @param name     the phase name
 * @param position the phase's display order within its project
 */
public record PortfolioPhaseEntry(long phaseId, String name, int position) {
}
