package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.DependencyLinkType;

import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST .../gantt/dependencies} (US22.4.3) — creates a typed dependency link
 * between two tasks of the same project.
 *
 * <p>The {@code linkType} is one of FS / SS / FF / SF; when omitted it defaults to {@code FS} (AC:
 * «&nbsp;typé FS par défaut&nbsp;»). The {@code lagMinutes} is a signed offset expressed in
 * <strong>worked minutes</strong> on the successor task's calendar (decision D7, see
 * {@link DependencyService}): a positive value is a lag (retard), a negative value a lead (avance);
 * omitted means zero. Both endpoints must belong to the same project the caller is browsing —
 * enforced by {@link DependencyService}, not by this record.
 *
 * @param predecessorTaskId the predecessor task id (required)
 * @param successorTaskId   the successor task id (required, must differ from the predecessor)
 * @param linkType          the link type, or {@code null} for the {@code FS} default
 * @param lagMinutes        the signed lag/lead in worked minutes, or {@code null} for zero
 */
public record CreateDependencyRequest(
        @NotNull Long predecessorTaskId,
        @NotNull Long successorTaskId,
        DependencyLinkType linkType,
        Integer lagMinutes) {

    /**
     * Canonical constructor applying the {@code FS} default and the zero-lag default so downstream
     * code never sees a {@code null} link type or lag.
     */
    public CreateDependencyRequest {
        linkType = linkType == null ? DependencyLinkType.FS : linkType;
        lagMinutes = lagMinutes == null ? 0 : lagMinutes;
    }
}
