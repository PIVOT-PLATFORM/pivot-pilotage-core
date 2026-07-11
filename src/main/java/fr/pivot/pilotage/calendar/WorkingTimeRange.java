package fr.pivot.pilotage.calendar;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One intra-day working-time range of a calendar (US22.4.5), expressed as whole hours
 * {@code [startHour, endHour)} on a 24-hour clock — the engine granularity is the worked hour
 * ({@code fr.pivot.pilotage.schedule.engine.WorkingCalendar}). Serialised into the persisted
 * {@code working_time} JSONB as an {@code ["HH:00","HH:00"]} pair, reusing the existing
 * {@code {"ranges":[...]}} convention.
 *
 * <p>Bean-validation guards the coarse bounds (0..24); the finer semantic checks (start&nbsp;&lt;&nbsp;end,
 * whole-hour alignment) are enforced in {@link CalendarService} so they surface as the domain
 * {@code 422} rather than a generic binding error.
 *
 * @param startHour inclusive start hour (0..23)
 * @param endHour   exclusive end hour (1..24), strictly greater than {@code startHour}
 */
public record WorkingTimeRange(
        @NotNull @Min(0) @Max(23) Integer startHour,
        @NotNull @Min(1) @Max(24) Integer endHour) {
}
