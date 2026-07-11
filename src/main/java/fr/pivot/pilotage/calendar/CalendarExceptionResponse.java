package fr.pivot.pilotage.calendar;

import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for one calendar-exception day (US22.4.5) — never the JPA entity directly (CLAUDE.md
 * §Standards). One row corresponds to one derogatory day; an interval added via
 * {@link AddExceptionRequest} yields one such response per day.
 *
 * @param exceptionId   stable exception id
 * @param calendarId    parent calendar id
 * @param exceptionDate the derogatory day
 * @param working       {@code true} if exceptionally worked, {@code false} if off
 * @param ranges        specific whole-hour ranges when worked (empty ⇒ calendar default), else empty
 */
public record CalendarExceptionResponse(
        long exceptionId,
        long calendarId,
        LocalDate exceptionDate,
        boolean working,
        List<WorkingTimeRange> ranges) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code ranges} (SpotBugs
     * {@code EI_EXPOSE_REP}).
     */
    public CalendarExceptionResponse {
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }
}
