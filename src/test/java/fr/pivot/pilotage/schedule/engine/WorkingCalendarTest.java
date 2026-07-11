package fr.pivot.pilotage.schedule.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkingCalendar} — the worked-hour granularity core of the engine
 * (EN22.1b). Anchored to a Monday-Friday 09:00-17:00 (8h/day) calendar with dates in the past
 * (2024-01-01 is a Monday); no {@code now()} is consulted.
 */
class WorkingCalendarTest {

    private final WorkingCalendar cal = WorkingCalendar.standardBusiness(1L);

    private static Instant at(final int year, final int month, final int day, final int hour) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant();
    }

    @Test
    void advanceOneWorkedDayLandsOnSameDayFinish() {
        // Monday 09:00 + 480 worked minutes = Monday 17:00.
        assertThat(cal.advance(at(2024, 1, 1, 9), 480)).isEqualTo(at(2024, 1, 1, 17));
    }

    @Test
    void advanceSpillsToNextWorkingDay() {
        // Monday 09:00 + 481 min = Tuesday 09:01 (1 min into the next working day).
        final Instant result = cal.advance(at(2024, 1, 1, 9), 481);
        assertThat(result).isEqualTo(at(2024, 1, 2, 9).plusSeconds(60));
    }

    @Test
    void advanceSkipsTheWeekend() {
        // Friday 09:00 + 480 = Friday 17:00; +481 jumps to Monday 09:01.
        assertThat(cal.advance(at(2024, 1, 5, 9), 480)).isEqualTo(at(2024, 1, 5, 17));
        assertThat(cal.advance(at(2024, 1, 5, 9), 481)).isEqualTo(at(2024, 1, 8, 9).plusSeconds(60));
    }

    @Test
    void snapForwardMovesOutOfNonWorkingTime() {
        // Saturday any time snaps to Monday 09:00.
        assertThat(cal.snapForward(at(2024, 1, 6, 12))).isEqualTo(at(2024, 1, 8, 9));
        // 20:00 on a working day snaps to next day 09:00.
        assertThat(cal.snapForward(at(2024, 1, 1, 20))).isEqualTo(at(2024, 1, 2, 9));
    }

    @Test
    void retreatIsTheInverseOfAdvanceForWholeDays() {
        // Wednesday 17:00 - 480 worked min = Wednesday 09:00.
        assertThat(cal.retreat(at(2024, 1, 3, 17), 480)).isEqualTo(at(2024, 1, 3, 9));
        // Monday 09:00 - 480 = previous Friday 09:00 (skips weekend backward).
        assertThat(cal.retreat(at(2024, 1, 8, 17), 480 * 3)).isEqualTo(at(2024, 1, 4, 9));
    }

    @Test
    void workedMinutesBetweenCountsOnlyWorkingTime() {
        // Monday 09:00 to Wednesday 17:00 = 3 * 480 = 1440 worked minutes (weekend excluded when spanned).
        assertThat(cal.workedMinutesBetween(at(2024, 1, 1, 9), at(2024, 1, 3, 17))).isEqualTo(1440);
        // Friday 09:00 to Monday 09:00 spans a weekend: only Friday's 480 minutes count.
        assertThat(cal.workedMinutesBetween(at(2024, 1, 5, 9), at(2024, 1, 8, 9))).isEqualTo(480);
    }

    @Test
    void snapToHourRoundsUp() {
        assertThat(WorkingCalendar.snapToHour(0)).isZero();
        assertThat(WorkingCalendar.snapToHour(1)).isEqualTo(60);
        assertThat(WorkingCalendar.snapToHour(60)).isEqualTo(60);
        assertThat(WorkingCalendar.snapToHour(61)).isEqualTo(120);
    }

    @Test
    void exceptionMakesADayOff() {
        // Mark Tuesday 2024-01-02 as a holiday; advancing across it skips to Wednesday.
        final WorkingCalendar withHoliday = new WorkingCalendar(2L, 0b0011111,
                List.of(new int[] {9 * 60, 17 * 60}),
                Map.of(LocalDate.of(2024, 1, 2),
                        new WorkingCalendar.DayExceptionModel(false, List.of())));
        assertThat(withHoliday.advance(at(2024, 1, 1, 9), 481))
                .isEqualTo(at(2024, 1, 3, 9).plusSeconds(60));
    }

    @Test
    void negativeMinutesRejected() {
        assertThatThrownBy(() -> cal.advance(at(2024, 1, 1, 9), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonWholeHourRangeRejected() {
        assertThatThrownBy(() -> new WorkingCalendar(3L, 0b0011111,
                List.of(new int[] {9 * 60 + 30, 17 * 60}), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workingDaysOfWeekReflectsMask() {
        assertThat(cal.workingDaysOfWeek()).hasSize(5);
    }
}
