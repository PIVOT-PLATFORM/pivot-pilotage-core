package fr.pivot.pilotage.calendar;

import fr.pivot.pilotage.schedule.CalendarScope;

/**
 * Response DTO of {@code GET .../tasks/{taskId}/effective-calendar} (US22.4.5) — the calendar that
 * actually governs a task's (or a task/resource pair's) working time, after applying the resolution
 * priority <strong>resource &gt; task &gt; project</strong> (EN22.1, decision D7).
 *
 * <p>{@code resolvedFrom} names the level that won: {@link CalendarScope#RESOURCE} when a resource's
 * own calendar applied (a {@code resourceRef} was supplied and matched a resource calendar),
 * {@link CalendarScope#TASK} when the task carried its own calendar override, otherwise
 * {@link CalendarScope#PROJECT} — the project's default calendar, the always-present fallback.
 *
 * @param calendarId   the winning calendar id
 * @param resolvedFrom the level that produced the winning calendar (resource / task / project)
 * @param calendar     the winning calendar's full definition (never {@code null})
 */
public record EffectiveCalendarResponse(
        long calendarId,
        CalendarScope resolvedFrom,
        CalendarResponse calendar) {
}
