package fr.pivot.pilotage.calendar;

import fr.pivot.pilotage.schedule.CalendarScope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body of {@code POST .../calendars} (US22.4.5) — creates a working-time calendar.
 *
 * <p>The {@code scope} selects the level the calendar applies to (project / task / resource). The
 * {@code projectId} is optional: {@code null} makes a reusable tenant/base calendar (typically a
 * shared {@code RESOURCE} calendar), a value scopes it to that project. The {@code workingDays} are
 * the ISO week days considered worked; the {@code ranges} are the intra-day whole-hour ranges,
 * serialised into the {@code working_time} JSONB via the existing {@code {"ranges":[...]}} convention.
 *
 * @param scope       the calendar scope (required)
 * @param projectId   the owning project id, or {@code null} for a reusable calendar
 * @param name        the calendar name (required, max 255)
 * @param workingDays the ISO week days worked (1=Mon..7=Sun), at least one
 * @param ranges      the intra-day whole-hour working ranges, at least one
 */
public record CreateCalendarRequest(
        @NotNull CalendarScope scope,
        Long projectId,
        @NotBlank @Size(max = 255) String name,
        @NotEmpty List<@NotNull Integer> workingDays,
        @NotEmpty List<@Valid WorkingTimeRange> ranges) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies of the list fields (SpotBugs
     * {@code EI_EXPOSE_REP}).
     */
    public CreateCalendarRequest {
        workingDays = workingDays == null ? List.of() : List.copyOf(workingDays);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }
}
