package fr.pivot.pilotage.calendar;

import fr.pivot.pilotage.schedule.CalendarScope;

import java.util.List;

/**
 * Response DTO for one calendar (US22.4.5) — never the JPA entity directly (CLAUDE.md §Standards).
 * Decodes the persisted {@code working_days_mask} bitmask and the {@code working_time} JSONB back
 * into caller-friendly lists (ISO week days, whole-hour ranges).
 *
 * @param calendarId  stable calendar id
 * @param projectId   owning project id, or {@code null} for a reusable calendar
 * @param scope       the calendar scope (project / task / resource)
 * @param name        the calendar name
 * @param workingDays the ISO week days worked (1=Mon..7=Sun), ascending
 * @param ranges      the intra-day whole-hour working ranges
 */
public record CalendarResponse(
        long calendarId,
        Long projectId,
        CalendarScope scope,
        String name,
        List<Integer> workingDays,
        List<WorkingTimeRange> ranges) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies of the list fields (SpotBugs
     * {@code EI_EXPOSE_REP}).
     */
    public CalendarResponse {
        workingDays = workingDays == null ? List.of() : List.copyOf(workingDays);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }
}
