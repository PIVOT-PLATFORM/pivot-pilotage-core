package fr.pivot.pilotage.calendar;

/**
 * Thrown when a calendar endpoint (US22.4.5) is asked to operate on a calendar (or one of its
 * exceptions) that does not resolve within the requesting {@code (tenantId, teamId)} boundary.
 *
 * <p>Deliberately a <strong>single</strong> exception for every isolation failure — unknown tenant,
 * unknown team, cross-team calendar, unknown calendar, unknown exception — all collapse to the same
 * bodyless {@code 404} (CLAUDE.md §Isolation tenant, non-disclosure). Mirrors
 * {@code fr.pivot.pilotage.gantt.WbsProjectNotFoundException}.
 */
public class CalendarNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a calendar not visible under the given tenant/team.
     *
     * @param calendarId the {@code pilotage.calendar.id} that was not found
     * @param tenantId   the requesting tenant's {@code public.tenants.id}
     * @param teamId     the requesting team's {@code public.teams.id}
     */
    public CalendarNotFoundException(final long calendarId, final long tenantId, final long teamId) {
        super("No calendar " + calendarId + " visible to tenant " + tenantId + "/team " + teamId);
    }
}
