package fr.pivot.pilotage.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body of {@code POST .../calendars/{calendarId}/exceptions} (US22.4.5) — adds a derogatory
 * period to a calendar. Modelled as a <strong>dated interval</strong> ({@code startDate}..{@code endDate},
 * both inclusive) per the fiche note («&nbsp;un intervalle daté attaché au calendrier&nbsp;»); the
 * service expands it into one {@code calendar_exception} row per day.
 *
 * <p>{@code working=false} marks the whole interval as off (public holiday / closure). {@code working=true}
 * marks it as exceptionally worked, then {@code ranges} carries its specific whole-hour working ranges
 * (empty ⇒ the calendar's default ranges apply). An {@code endDate} strictly before {@code startDate}
 * is rejected {@code 422} (error AC).
 *
 * @param startDate the first day of the derogatory interval (inclusive, required)
 * @param endDate   the last day of the derogatory interval (inclusive, required)
 * @param working   {@code true} if exceptionally worked, {@code false} if off (required)
 * @param ranges    specific whole-hour ranges when {@code working} is {@code true}; may be empty/null
 */
public record AddExceptionRequest(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull Boolean working,
        List<@Valid WorkingTimeRange> ranges) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code ranges} (SpotBugs
     * {@code EI_EXPOSE_REP}).
     */
    public AddExceptionRequest {
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }
}
