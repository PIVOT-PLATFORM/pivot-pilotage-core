package fr.pivot.pilotage.dashboard;

/**
 * One widget entry of {@link SaveDashboardRequest} (US23.2.2 AC3 — "ajout/retrait de widget,
 * disposition").
 *
 * <p>{@code widgetType} is typed directly as {@link DashboardWidgetType} — mirrors
 * {@code fr.pivot.pilotage.roadmap.UpdateInitiativeHorizonRequest#horizon()}'s established
 * convention: an unknown JSON token is rejected by Jackson before this record is even constructed;
 * {@link DashboardService} only needs to guard the explicit {@code null} case
 * ({@code WIDGET_TYPE_REQUIRED}). Likewise {@code applicationId} is a boxed {@link Long} (not a
 * primitive) so "field omitted" is distinguishable from "application id 0".
 *
 * <p>Grid fields are primitive {@code int}: an omitted JSON field defaults to {@code 0}, which for
 * {@code gridWidth}/{@code gridHeight} (valid range 1..4) is itself already out of bounds — the
 * disposition validation in {@link DashboardService} catches this naturally, no separate
 * "required" case needed for these four fields.
 *
 * @param widgetType    the widget's type; required
 * @param applicationId the target application; required
 * @param position      rendering order among the dashboard's widgets (0-based)
 * @param gridRow       grid row (0-based, unbounded)
 * @param gridColumn    grid column (0..3, 4-column grid)
 * @param gridWidth     grid width in columns (1..4), must not extend past the grid's right edge
 * @param gridHeight    grid height in rows (1..4)
 */
public record SaveDashboardWidgetRequest(
        DashboardWidgetType widgetType,
        Long applicationId,
        int position,
        int gridRow,
        int gridColumn,
        int gridWidth,
        int gridHeight) {
}
