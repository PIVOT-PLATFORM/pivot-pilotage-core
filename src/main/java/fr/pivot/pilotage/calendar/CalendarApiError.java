package fr.pivot.pilotage.calendar;

/**
 * Minimal error body returned by {@link CalendarExceptionHandler} for the calendar validation errors
 * that must carry an explicit, caller-facing message (unlike the bodyless 404s used for non-disclosed
 * tenant/team/project/calendar isolation failures — see {@link CalendarNotFoundException}).
 *
 * <p>Package-local mirror of {@code fr.pivot.pilotage.gantt.WbsApiError}, kept in this package so the
 * calendar error contract is self-contained and independent of the gantt package's evolution.
 *
 * @param code    a stable machine-readable error code (e.g. {@code INVALID_CALENDAR_EXCEPTION})
 * @param message a human-readable message describing the validation failure
 */
public record CalendarApiError(String code, String message) {
}
