package fr.pivot.pilotage.calendar;

/**
 * Thrown by {@link CalendarController} when {@link CalendarEditPolicy#isAuthorized()} denies a
 * calendar write (create/update/delete a calendar, add/remove an exception — US22.4.5). Mapped to
 * HTTP {@code 403} by {@link CalendarExceptionHandler}. Mirrors
 * {@code fr.pivot.pilotage.gantt.WbsEditForbiddenException}.
 */
public class CalendarEditForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with a message documenting the current (deny-all) posture. */
    public CalendarEditForbiddenException() {
        super("Calendar edition requires a project administration role (chef de projet); a planning "
                + "contributor may only read calendars. No membership mechanism is wired yet "
                + "(pivot-core-starter gap, TODO-SETUP.md §5) — denying by default");
    }
}
