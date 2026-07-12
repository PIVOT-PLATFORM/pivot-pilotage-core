package fr.pivot.pilotage.dashboard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body of {@code PUT .../dashboard} (US23.2.2 AC3) — replaces the caller's whole dashboard
 * layout in one idempotent call (profile label, view mode and the complete widget list). A
 * full-replace design, not per-widget CRUD endpoints: the AC only describes "modification du
 * layout (ajout/retrait de widget, disposition)" as a single save action, and a widget list is
 * small enough that resubmitting it whole avoids partial-update race conditions entirely (PO Agent
 * decision, Gate 1 — the backlog file does not itself specify the endpoint shape).
 *
 * <p>{@code profile}: plain {@code @NotBlank} (like
 * {@code fr.pivot.pilotage.roadmap.CreateLaneRequest#name()}) — see {@link DashboardConfig} class
 * doc for why this is a free label, not a validated enum. {@code viewMode} is validated by
 * {@link DashboardService} instead of {@code @NotNull} (see
 * {@link InvalidDashboardConfigException}), so the 400 carries a caller-facing {@link ApiError}
 * body.
 *
 * @param profile  caller-supplied persona label; required, non-blank, max 64 chars (matches
 *                 {@code pilotage.dashboard_config.profile})
 * @param viewMode the rendering mode; required (validated in the service, see class doc)
 * @param widgets  the complete widget list, in display order; {@code null} is normalized to an
 *                 empty list by the canonical constructor (clearing all widgets is a legitimate
 *                 "retrait" of every widget, not an error)
 */
public record SaveDashboardRequest(
        @NotBlank @Size(max = 64) String profile,
        DashboardViewMode viewMode,
        List<SaveDashboardWidgetRequest> widgets) {

    /**
     * Canonical constructor: normalizes a {@code null} widget list to empty and takes a
     * defensive, unmodifiable copy (SpotBugs {@code EI_EXPOSE_REP}/{@code EI_EXPOSE_REP2},
     * mirrors {@code fr.pivot.pilotage.consolidation.ApplicationConsolidation}) — so
     * {@link DashboardService} never has to special-case {@code null} itself.
     */
    public SaveDashboardRequest {
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
    }
}
