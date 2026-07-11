/**
 * Working-time calendar management (F22.4 — US22.4.5 «&nbsp;Calendriers ouvrés &amp; exceptions&nbsp;»).
 *
 * <p>This package owns the CRUD of {@code pilotage.calendar} rows (project / task / resource scope,
 * working-days bitmask, working-time ranges) and their {@code pilotage.calendar_exception}
 * derogatory days (public holidays off / exceptionally-worked days), plus the <em>effective
 * calendar resolution contract</em> — priority <strong>resource &gt; task &gt; project</strong>
 * (EN22.1, decision D7). It does <strong>not</strong> re-implement the scheduling projection: the
 * pure engine {@code fr.pivot.pilotage.schedule.engine.WorkingCalendar} already maps working days,
 * exceptions and working-time onto the worked-minute axis, and {@code SchedulingService} already
 * consumes calendars at task&gt;project priority. This package adds the management surface and the
 * resource-level resolution the engine cannot express without an assignment.
 *
 * <p>The REST layer mirrors {@code fr.pivot.pilotage.gantt} exactly: thin controller, role gate
 * delegated to {@link fr.pivot.pilotage.calendar.CalendarEditPolicy} (fail-closed until
 * {@code pivot-core-starter} publishes project membership — CLAUDE.md §gap, TODO-SETUP §5),
 * package-scoped exception handler, immutable DTOs (never JPA entities), tenant/team taken from the
 * path (gap era) with cross-tenant/team failures collapsing to a non-disclosing {@code 404}.
 */
package fr.pivot.pilotage.calendar;
