package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.DependencyLinkType;

import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code PUT .../gantt/dependencies/{dependencyId}} (US22.4.3) — retypes an existing
 * link and/or changes its lag/lead, without moving its endpoints.
 *
 * <p>Only the mutable facets of a dependency are editable: the two endpoint tasks are fixed for the
 * lifetime of the edge (changing them is a delete + create, so a would-be cycle is always evaluated
 * against a fresh graph). The {@code lagMinutes} is a signed offset in <strong>worked minutes</strong>
 * on the successor task's calendar (decision D7). The recompute + cycle rejection runs exactly as on
 * creation (see {@link DependencyService}).
 *
 * @param linkType   the new link type (required)
 * @param lagMinutes the new signed lag/lead in worked minutes, or {@code null} for zero
 */
public record UpdateDependencyRequest(
        @NotNull DependencyLinkType linkType,
        Integer lagMinutes) {

    /** Canonical constructor applying the zero-lag default so downstream code never sees a null lag. */
    public UpdateDependencyRequest {
        lagMinutes = lagMinutes == null ? 0 : lagMinutes;
    }
}
