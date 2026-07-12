package fr.pivot.pilotage.dashboard;

/**
 * The two indicator families named by US23.2.2's AC2 ("des indicateurs source (avancement,
 * météo) en état de tension") that a {@link PortfolioIndicatorSource} may report a tension level
 * for. Kept as a single parameterized SPI method (rather than two separate SPI interfaces) so a
 * future producer can implement one or both kinds through the same seam.
 */
public enum PortfolioIndicatorKind {

    /** Schedule/advancement tension overlay for {@link DashboardWidgetType#PORTFOLIO_STATUS_SUMMARY}. */
    PROGRESS,

    /** "Météo" tension for {@link DashboardWidgetType#WEATHER_ALERTS}. */
    WEATHER
}
