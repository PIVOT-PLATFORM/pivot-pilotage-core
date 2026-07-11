package fr.pivot.pilotage.schedule.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CalendarWorkingTime} — the whole-hour working-time JSON parser (EN22.1b).
 */
class CalendarWorkingTimeTest {

    private static final List<int[]> DEFAULT = List.of(new int[] {9 * 60, 17 * 60});

    @Test
    void emptyPayloadFallsBackToDefault() {
        assertThat(CalendarWorkingTime.parse(null, DEFAULT)).isEqualTo(DEFAULT);
        assertThat(CalendarWorkingTime.parse("", DEFAULT)).isEqualTo(DEFAULT);
        assertThat(CalendarWorkingTime.parse("{}", DEFAULT)).isEqualTo(DEFAULT);
        assertThat(CalendarWorkingTime.parse("{ }", DEFAULT)).isEqualTo(DEFAULT);
    }

    @Test
    void parsesExplicitRanges() {
        final List<int[]> ranges = CalendarWorkingTime.parse(
                "{\"ranges\":[[\"09:00\",\"12:00\"],[\"13:00\",\"17:00\"]]}", DEFAULT);
        assertThat(ranges).hasSize(2);
        assertThat(ranges.get(0)).containsExactly(9 * 60, 12 * 60);
        assertThat(ranges.get(1)).containsExactly(13 * 60, 17 * 60);
    }

    @Test
    void rejectsNonWholeHourTimes() {
        assertThatThrownBy(() -> CalendarWorkingTime.parse(
                "{\"ranges\":[[\"09:30\",\"17:00\"]]}", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
