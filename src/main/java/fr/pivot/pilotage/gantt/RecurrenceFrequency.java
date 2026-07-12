package fr.pivot.pilotage.gantt;

/**
 * Recurrence cadence of a periodic Gantt task (US22.4.6), translated server-side into the
 * iCalendar-style {@code recurrence_rule} persisted on the series
 * {@link fr.pivot.pilotage.schedule.Task} ({@code node_kind=RECURRING}, EN22.1a frozen contract
 * &sect;a).
 *
 * <p>Kept minimal &mdash; daily/weekly/monthly cover the backlog's own example ("comité hebdo") and
 * the wider MS-Project-parity target without prematurely committing to a full RFC&nbsp;5545 RRULE
 * surface (yearly, by-day, by-month-day, &hellip;); a broader cadence can be added later as a new
 * enum constant without an API break.
 */
public enum RecurrenceFrequency {

    /** Every {@code intervalCount} day(s). */
    DAILY,

    /** Every {@code intervalCount} week(s), anchored on the first occurrence's day of week. */
    WEEKLY,

    /** Every {@code intervalCount} month(s), anchored on the first occurrence's day of month. */
    MONTHLY
}
