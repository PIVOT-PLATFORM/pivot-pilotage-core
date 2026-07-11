package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.time.LocalDate;
import java.time.temporal.IsoFields;

/**
 * Fuzzy time-scale helper for the roadmap-rapide view (US22.3.2 — "Échelle de temps floue").
 *
 * <p>The scale itself is not a new persisted type: it <strong>is</strong> a
 * {@link TemporalPrecision} (the same SEMESTER/QUARTER/MONTH/WEEK/DAY grain already carried by the
 * temporal graph, EN22.1a). This class only holds the pure, side-effect-free projection rules that
 * turn a stored fuzzy period into the <em>period bounds</em> a bar snaps to at a given scale — the
 * AC "les barres s'alignent sur des bornes de période, pas sur des dates au jour".
 *
 * <p><strong>Projection only, never a mutation.</strong> Snapping is applied at read time on the
 * {@link InitiativeResponse}; it never rewrites {@code fuzzy_period_start}/{@code fuzzy_period_end}
 * in the database. That is precisely what lets a scale change (e.g. QUARTER → MONTH) be applied
 * "sans supprimer ni tronquer les données de période existantes" (error AC): the raw stored period
 * is preserved untouched, only its rendered envelope changes.
 */
final class RoadmapScale {

    private RoadmapScale() {
    }

    /**
     * Snaps a stored fuzzy period to the period bounds of the given scale — the lower bound to the
     * first day of its containing period, the upper bound to the last day of its containing period.
     * A {@code null} bound stays {@code null} (an initiative with no precise date keeps none — AC
     * "aucune date exacte n'est imposée").
     *
     * @param scale the roadmap view scale (the effective {@link TemporalPrecision})
     * @param start the stored fuzzy period lower bound, or {@code null}
     * @param end   the stored fuzzy period upper bound, or {@code null}
     * @return the snapped {@link PeriodBounds} (each bound {@code null} when its source is
     *         {@code null})
     */
    static PeriodBounds snap(final TemporalPrecision scale, final LocalDate start, final LocalDate end) {
        return new PeriodBounds(
                start == null ? null : periodStart(scale, start),
                end == null ? null : periodEnd(scale, end));
    }

    /**
     * Returns the first day of the period (at the given scale) that contains {@code date}.
     *
     * @param scale the scale grain
     * @param date  the date to snap down
     * @return the first day of the containing period
     */
    private static LocalDate periodStart(final TemporalPrecision scale, final LocalDate date) {
        return switch (scale) {
            case SEMESTER -> LocalDate.of(date.getYear(), date.getMonthValue() <= 6 ? 1 : 7, 1);
            case QUARTER -> date.with(IsoFields.DAY_OF_QUARTER, 1L);
            case MONTH -> date.withDayOfMonth(1);
            case WEEK -> date.with(java.time.DayOfWeek.MONDAY);
            case DAY -> date;
        };
    }

    /**
     * Returns the last day of the period (at the given scale) that contains {@code date}.
     *
     * @param scale the scale grain
     * @param date  the date to snap up
     * @return the last day of the containing period
     */
    private static LocalDate periodEnd(final TemporalPrecision scale, final LocalDate date) {
        return switch (scale) {
            case SEMESTER -> date.getMonthValue() <= 6
                    ? LocalDate.of(date.getYear(), 6, 30)
                    : LocalDate.of(date.getYear(), 12, 31);
            case QUARTER -> {
                final LocalDate quarterStart = date.with(IsoFields.DAY_OF_QUARTER, 1L);
                yield quarterStart.plusMonths(2).withDayOfMonth(quarterStart.plusMonths(2).lengthOfMonth());
            }
            case MONTH -> date.withDayOfMonth(date.lengthOfMonth());
            case WEEK -> date.with(java.time.DayOfWeek.SUNDAY);
            case DAY -> date;
        };
    }
}
