package fr.pivot.pilotage.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body of {@code PUT .../calendars/{calendarId}} (US22.4.5) — updates a calendar's mutable
 * attributes: its name, its working days and its intra-day working ranges. The {@code scope},
 * {@code projectId} and tenant/team of an existing calendar are immutable identity/placement and are
 * never updated here (a scope change would be a different calendar).
 *
 * @param name        the new calendar name (required, max 255)
 * @param workingDays the ISO week days worked (1=Mon..7=Sun), at least one
 * @param ranges      the intra-day whole-hour working ranges, at least one
 */
public record UpdateCalendarRequest(
        @NotBlank @Size(max = 255) String name,
        @NotEmpty List<@NotNull Integer> workingDays,
        @NotEmpty List<@Valid WorkingTimeRange> ranges) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies of the list fields (SpotBugs
     * {@code EI_EXPOSE_REP}).
     */
    public UpdateCalendarRequest {
        workingDays = workingDays == null ? List.of() : List.copyOf(workingDays);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }
}
