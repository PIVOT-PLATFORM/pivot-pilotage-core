package fr.pivot.pilotage.dashboard;

/**
 * Response view of one rendered dashboard widget (US23.2.2) — never the {@link DashboardWidget}
 * JPA entity directly (CLAUDE.md §Standards).
 *
 * @param id            the widget id
 * @param widgetType    the widget's type
 * @param applicationId the target application
 * @param position      rendering order among the dashboard's widgets
 * @param gridRow       grid row
 * @param gridColumn    grid column
 * @param gridWidth     grid width in columns
 * @param gridHeight    grid height in rows
 * @param indicator     the rendered indicator payload for this widget
 */
public record DashboardWidgetResponse(
        long id,
        DashboardWidgetType widgetType,
        long applicationId,
        int position,
        int gridRow,
        int gridColumn,
        int gridWidth,
        int gridHeight,
        DashboardIndicatorView indicator) {
}
