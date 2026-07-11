package fr.pivot.pilotage.calendar;

/**
 * Authorization policy for every calendar <strong>write</strong> (US22.4.5 — create/update/delete a
 * calendar, add/remove an exception). Security AC: «&nbsp;seul un utilisateur avec un rôle
 * d'administration du projet peut créer/modifier un calendrier projet ou ressource ; un contributeur
 * planning ne peut que consulter les calendriers&nbsp;» — a read-only role receives {@code 403}.
 *
 * <p><strong>Extension point, deliberately not wired to a real role/membership check yet</strong> —
 * mirrors {@code fr.pivot.pilotage.gantt.WbsEditPolicy} exactly. {@code pivot-core-starter} (the
 * module exposing {@code TenantContext} and roles/project membership) is not published (CLAUDE.md
 * §gap, {@code TODO-SETUP.md} §5). The only implementation wired today, {@link DenyAllCalendarEditPolicy},
 * therefore <strong>always denies</strong> — fail-closed, never a hardcoded role and never a silent
 * bypass. Once the starter publishes project membership, replace the wired bean with a real
 * implementation; {@link CalendarController} and {@link CalendarService} do not need to change.
 *
 * <p>Read endpoints (list/read a calendar and its exceptions) are <strong>not</strong> gated by this
 * policy — they remain governed by the tenant/team/project isolation check ({@link CalendarNotFoundException},
 * 404 non-disclosure).
 */
public interface CalendarEditPolicy {

    /**
     * Returns whether the current caller is authorized to modify calendars.
     *
     * @return {@code true} if authorized; {@code false} maps to HTTP 403 at the controller
     */
    boolean isAuthorized();
}
